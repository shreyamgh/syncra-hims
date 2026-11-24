package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admissions and the 50-bed layout. Each test uses its own beds so occupancy
 * left by one test never affects another.
 */
class IpdApiIntegrationTest extends IntegrationTestSupport {

    @Test
    void admitsIntoABedAndShowsItOnTheBoard() throws Exception {
        JsonNode patient = registerPatient("M", LocalDate.of(1968, 7, 7));
        admit(patient, "GMW-03")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admissionNumber").value(Matchers.matchesPattern("^ADM/\\d{2}-\\d{2}/\\d{6}$")))
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.currentBed.bedCode").value("GMW-03"))
                .andExpect(jsonPath("$.currentBed.bedCategory").value("GENERAL"));

        perform(get("/api/v1/ipd/beds").param("ward", "GMW"), NURSE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$[?(@.bedCode == 'GMW-03')].bedStatus").value("OCCUPIED"))
                .andExpect(jsonPath("$[?(@.bedCode == 'GMW-03')].occupant.uhid").value(patient.get("uhid").asText()));
        perform(get("/api/v1/ipd/beds/available").param("gender", "M"), NURSE)
                .andExpect(jsonPath("$[*].bedCode", Matchers.not(Matchers.hasItem("GMW-03"))))
                .andExpect(jsonPath("$[*].wardCode", Matchers.not(Matchers.hasItem("GFW"))));
    }

    @Test
    void wardCensusCoversAllFiftyBeds() throws Exception {
        JsonNode census = body(perform(get("/api/v1/ipd/wards"), BILLING).andExpect(status().isOk()));
        int total = 0;
        for (JsonNode ward : census) {
            total += ward.get("total").asInt();
        }
        assertThat(total).isEqualTo(50);
        assertThat(census).hasSize(7);
    }

    @Test
    void refusesAFemalePatientInTheMaleWardAndRollsBackTheAdmission() throws Exception {
        JsonNode patient = registerPatient("F", LocalDate.of(1990, 10, 10));
        admit(patient, "GMW-05")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BED_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("restricted to gender M")));

        long admissions = jdbc.sql("SELECT count(*) FROM ipd.admission WHERE patient_id = :p")
                .param("p", UUID.fromString(patient.get("id").asText())).query(Long.class).single();
        assertThat(admissions).isZero();
    }

    @Test
    void refusesAnOccupiedBedAndASecondAdmission() throws Exception {
        JsonNode first = registerPatient("M", LocalDate.of(1971, 1, 1));
        JsonNode second = registerPatient("F", LocalDate.of(1972, 2, 2));
        admit(first, "SPV-03").andExpect(status().isCreated());

        admit(second, "SPV-03")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BED_UNAVAILABLE"));
        admit(first, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ADMITTED"));
    }

    @Test
    void transferHousekeepingAndDischarge() throws Exception {
        JsonNode patient = registerPatient("F", LocalDate.of(1959, 3, 3));
        JsonNode admission = body(admit(patient, "ICU-02").andExpect(status().isCreated()));
        String url = "/api/v1/ipd/admissions/" + admission.get("admissionId").asText();

        postJson(url + "/bed", Map.of("bedCode", "PVT-02", "reason", "TRANSFER"), NURSE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBed.bedCode").value("PVT-02"))
                .andExpect(jsonPath("$.currentBed.bedCategory").value("PRIVATE"));
        postJson(url + "/bed", Map.of("bedCode", "PVT-02"), NURSE)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_BED"));
        assertThat(bedStatus("ICU-02")).isEqualTo("CLEANING");
        assertThat(bedStatus("PVT-02")).isEqualTo("OCCUPIED");

        bedStatusChange("ICU-02", "AVAILABLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        bedStatusChange("PVT-02", "AVAILABLE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_BED_TRANSITION"));
        bedStatusChange("PVT-02", "OCCUPIED").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_MANUAL_STATUS"));

        postJson(url + "/discharge-initiation", Map.of(), asDoctor("GMED"))
                .andExpect(jsonPath("$.status").value("DISCHARGE_INITIATED"));
        postJson(url + "/discharge", Map.of("outcome", "DISCHARGED"), RECEPTION)
                .andExpect(status().isForbidden());
        postJson(url + "/discharge", Map.of("outcome", "DISCHARGED"), NURSE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCHARGED"))
                .andExpect(jsonPath("$.dischargedAt").exists())
                .andExpect(jsonPath("$.currentBed").doesNotExist());
        assertThat(bedStatus("PVT-02")).isEqualTo("CLEANING");
        assertThat(jdbc.sql("SELECT status FROM clinical.encounter WHERE id = :id")
                .param("id", UUID.fromString(admission.get("encounterId").asText()))
                .query(String.class).single()).isEqualTo("FINISHED");

        bedStatusChange("PVT-02", "AVAILABLE").andExpect(status().isOk());
        postJson(url + "/discharge", Map.of("outcome", "DISCHARGED"), NURSE)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMISSION_CLOSED"));
    }

    @Test
    void admitsWithoutABedThenAllocatesOne() throws Exception {
        JsonNode patient = registerPatient("F", LocalDate.of(1994, 12, 12));
        JsonNode admission = body(admit(patient, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBed").doesNotExist()));
        postJson("/api/v1/ipd/admissions/" + admission.get("admissionId").asText() + "/bed",
                Map.of("bedCode", "gfw-04"), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBed.bedCode").value("GFW-04"));
        String reason = jdbc.sql("""
                SELECT reason FROM ipd.bed_assignment
                WHERE admission_id = :a AND released_at IS NULL
                """).param("a", UUID.fromString(admission.get("admissionId").asText())).query(String.class).single();
        assertThat(reason).isEqualTo("ADMISSION");
    }

    @Test
    void deathRecordedAtDischargeMarksThePatientDeceased() throws Exception {
        JsonNode patient = registerPatient("M", LocalDate.of(1940, 1, 1));
        JsonNode admission = body(admit(patient, "ICU-03").andExpect(status().isCreated()));
        postJson("/api/v1/ipd/admissions/" + admission.get("admissionId").asText() + "/discharge",
                Map.of("outcome", "EXPIRED"), asDoctor("GMED"))
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        perform(get("/api/v1/patients/" + patient.get("uhid").asText()), NURSE)
                .andExpect(jsonPath("$.deceased").value(true))
                .andExpect(jsonPath("$.deceasedAt").exists());
        postJson("/api/v1/opd/visits", Map.of("uhid", patient.get("uhid").asText(), "departmentCode", "GMED"),
                RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_DECEASED"));
    }

    private ResultActions admit(JsonNode patient, String bedCode) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("uhid", patient.get("uhid").asText());
        body.put("departmentCode", "GMED");
        body.put("admittingDoctorId", doctorIn("GMED").toString());
        body.put("admissionType", "EMERGENCY");
        body.put("provisionalDiagnosis", "Observation");
        body.put("attendant", Map.of("name", "Attendant", "relation", "Spouse", "mobile", randomMobile()));
        if (bedCode != null) {
            body.put("bedCode", bedCode);
        }
        return postJson("/api/v1/ipd/admissions", body, RECEPTION);
    }

    private ResultActions bedStatusChange(String bedCode, String target) throws Exception {
        return perform(put("/api/v1/ipd/beds/" + bedCode + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("status", target))), NURSE);
    }

    private String bedStatus(String bedCode) {
        return jdbc.sql("SELECT status FROM ipd.bed WHERE bed_code = :code")
                .param("code", bedCode).query(String.class).single();
    }
}
