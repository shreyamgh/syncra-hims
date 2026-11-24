package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.FakeUpstreams;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The drug-safety check is re-run when the doctor signs: serious alerts need a reason, every
 * alert is kept with how it was resolved, and the check sees the patient's allergies, age, sex,
 * weight and latest creatinine.
 */
class CdssAtSigningIntegrationTest extends IntegrationTestSupport {

    @AfterEach
    void resetCdss() {
        UPSTREAMS.cdssAlerts(List.of());
        UPSTREAMS.cdssDown(false);
    }

    @Test
    void seriousAlertsNeedAReasonAndEveryAlertIsRecorded() throws Exception {
        doctorIn("GMED");
        JsonNode patient = registerPatient("M", LocalDate.of(1956, 2, 2));
        String uhid = patient.get("uhid").asText();
        UUID patientId = UUID.fromString(patient.get("id").asText());
        String encounter = visit(uhid);
        postJson("/api/v1/emr/patients/" + uhid + "/allergies",
                Map.of("allergenType", "DRUG_CLASS", "allergenText", "Penicillin", "drugClass", "Penicillins"), NURSE)
                .andExpect(status().isCreated());
        creatinineResult(patientId, UUID.fromString(encounter), 2.4);

        UPSTREAMS.cdssAlerts(List.of(
                FakeUpstreams.alert("DRUG_DRUG", "MAJOR", "DDI-VKA-NSAID", "Increased risk of bleeding.", 0, 1),
                FakeUpstreams.alert("DRUG_DRUG", "MODERATE", "DDI-RAAS-NSAID", "Reduced antihypertensive effect.", 1, 2)));
        Map<String, Object> consultation = consultation();

        postJson(url(encounter), consultation, asDoctor("GMED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CDSS_OVERRIDE_REQUIRED"))
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].ruleCode").value("DDI-VKA-NSAID"));
        assertThat(count("SELECT count(*) FROM clinical.prescription WHERE encounter_id = :e", encounter))
                .as("nothing is saved").isZero();

        JsonNode sent = UPSTREAMS.lastCdssRequest();
        assertThat(sent.path("prescriptions")).hasSize(3);
        assertThat(sent.at("/prescriptions/0/drug_text").asText()).isEqualTo("Tab Warfarin 5 mg");
        assertThat(sent.at("/prescriptions/1/frequency").asText()).isEqualTo("TDS");
        assertThat(sent.at("/allergies/0/allergen").asText()).isEqualTo("Penicillin");
        assertThat(sent.at("/patient/sex").asText()).isEqualTo("M");
        assertThat(sent.at("/patient/age_years").asInt()).isGreaterThanOrEqualTo(70);
        assertThat(sent.at("/patient/weight_kg").decimalValue()).isEqualByComparingTo("72.5");
        assertThat(sent.at("/patient/serum_creatinine_mg_dl").decimalValue()).isEqualByComparingTo("2.4");

        consultation.put("cdssOverrides", List.of(Map.of("ruleCode", "DDI-VKA-NSAID",
                "reason", "Two days only for gout flare; INR check on day 3")));
        JsonNode signed = body(postJson(url(encounter), consultation, asDoctor("GMED")).andExpect(status().isCreated()));
        assertThat(signed.path("cdssAlerts")).hasSize(2);
        assertThat(signed.at("/cdssAlerts/0/resolution").asText()).isEqualTo("OVERRIDDEN");
        assertThat(signed.at("/cdssAlerts/0/overrideReason").asText()).startsWith("Two days only");
        assertThat(signed.at("/cdssAlerts/0/kbVersion").asText()).isEqualTo("test-kb-1");
        assertThat(signed.at("/cdssAlerts/1/resolution").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(signed.path("diagnoses")).hasSize(1);
        assertThat(signed.at("/diagnoses/0/icd10Code").asText()).isEqualTo("M10.9");
    }

    @Test
    void aShortOverrideReasonIsRefused() throws Exception {
        doctorIn("GMED");
        String encounter = visit(registerPatient("F", LocalDate.of(1990, 1, 1)).get("uhid").asText());
        UPSTREAMS.cdssAlerts(List.of(FakeUpstreams.alert("DRUG_ALLERGY", "CONTRAINDICATED", "ALG-AMOXICILLIN",
                "Recorded allergy.", 0)));
        Map<String, Object> consultation = consultation();
        consultation.put("cdssOverrides", List.of(Map.of("ruleCode", "ALG-AMOXICILLIN", "reason", "ok")));
        postJson(url(encounter), consultation, asDoctor("GMED")).andExpect(status().isBadRequest());
    }

    @Test
    void whenTheCdssIsDownThePrescriptionIsSignedAndTheGapAudited() throws Exception {
        doctorIn("GMED");
        String encounter = visit(registerPatient("M", LocalDate.of(1985, 1, 1)).get("uhid").asText());
        UPSTREAMS.cdssDown(true);
        postJson(url(encounter), consultation(), asDoctor("GMED")).andExpect(status().isCreated());
        assertThat(jdbc.sql("""
                SELECT details->>'cdssChecked' FROM audit.event_log
                WHERE entity_type = 'consultation' AND entity_id = :e
                """).param("e", encounter).query(String.class).single()).isEqualTo("false");
    }

    // ------------------------------------------------------------------ fixtures

    private static String url(String encounter) {
        return "/api/v1/emr/encounters/" + encounter + "/consultation";
    }

    private static Map<String, Object> consultation() {
        Map<String, Object> request = new HashMap<>();
        request.put("vitals", Map.of("weightKg", 72.5, "pulseBpm", 84));
        request.put("diagnoses", List.of(Map.of("description", "Acute gout", "icd10Code", "M10.9", "type", "PROVISIONAL",
                "primary", true)));
        request.put("prescription", Map.of("items", List.of(
                Map.of("drugText", "Tab Warfarin 5 mg", "dose", "1 tab", "frequency", "OD"),
                Map.of("drugText", "Tab Ibuprofen 400 mg", "dose", "1 tab", "frequency", "TDS", "durationDays", 2),
                Map.of("drugText", "Tab Telmisartan 40 mg", "dose", "1 tab", "frequency", "OD"))));
        request.put("completeVisit", false);
        return request;
    }

    private String visit(String uhid) throws Exception {
        return body(postJson("/api/v1/opd/visits", Map.of("uhid", uhid, "departmentCode", "GMED"), RECEPTION)
                .andExpect(status().isCreated())).get("encounterId").asText();
    }

    private void creatinineResult(UUID patientId, UUID encounterId, double value) {
        UUID doctor = doctorIn("GMED");
        jdbc.sql("""
                INSERT INTO clinical.service_order (encounter_id, patient_id, service_id, order_type, status, ordered_by,
                                                    completed_at, result_values, reported_by)
                SELECT :e, :p, id, 'LABORATORY', 'REPORTED', :d, now() - interval '2 days',
                       CAST(:values AS jsonb), :d FROM billing.service WHERE code = 'LAB-RFT'
                """).param("e", encounterId).param("p", patientId).param("d", doctor)
                .param("values", "[{\"code\":\"CREAT\",\"name\":\"Serum creatinine\",\"value\":" + value
                        + ",\"unit\":\"mg/dL\"}]").update();
    }

    private int count(String sql, String encounter) {
        return jdbc.sql(sql).param("e", UUID.fromString(encounter)).query(Integer.class).single();
    }
}
