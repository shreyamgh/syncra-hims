package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vaccinations: what may be recorded, and that the record stays trustworthy. */
class ImmunizationApiIntegrationTest extends IntegrationTestSupport {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Test
    void nurseRecordsAnInfantsVaccinationsAndTheHistoryShowsThem() throws Exception {
        LocalDate born = TODAY.minusWeeks(10);
        JsonNode visit = openVisit(born);
        String encounter = visit.get("encounterId").asText();
        String uhid = visit.get("uhid").asText();

        JsonNode vaccines = body(perform(get("/api/v1/emr/vaccines"), NURSE).andExpect(status().isOk()));
        assertThat(vaccines.findValuesAsText("code")).contains("BCG", "PENTA", "OPV", "MR");

        JsonNode penta = body(postJson(path(encounter), dose("PENTA", 1), NURSE).andExpect(status().isCreated()));
        assertThat(penta.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(penta.path("route").asText()).as("catalogue default").isEqualTo("IM");
        assertThat(penta.path("doseMl").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(penta.path("seriesDoses").asInt()).isEqualTo(3);

        Map<String, Object> bcg = new HashMap<>(Map.of("vaccineCode", "BCG", "doseNumber", 1,
                "administeredAt", born.atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toString(),
                "primarySource", false, "reportOrigin", "MCP card"));
        postJson(path(encounter), bcg, NURSE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.primarySource").value(false));

        postJson(path(encounter), Map.of("vaccineCode", "OPV", "doseNumber", 2, "status", "NOT_DONE",
                "notDoneReason", "Child unwell with fever; reschedule"), NURSE)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("NOT_DONE"));

        JsonNode history = body(perform(get("/api/v1/emr/patients/" + uhid + "/immunizations"), asDoctor("PAED"))
                .andExpect(status().isOk()));
        assertThat(history.path("immunizations").findValuesAsText("vaccineCode"))
                .containsExactlyInAnyOrder("PENTA", "BCG", "OPV");
        perform(get("/api/v1/emr/encounters/" + encounter), NURSE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.immunizations.length()").value(3));
    }

    @Test
    void unsafeOrInconsistentDosesAreRefused() throws Exception {
        String encounter = openVisit(TODAY.minusWeeks(14)).get("encounterId").asText();
        postJson(path(encounter), dose("PENTA", 1), NURSE).andExpect(status().isCreated());

        postJson(path(encounter), dose("PENTA", 1), NURSE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This dose of the vaccine is already recorded for the patient"));

        Map<String, Object> expired = new HashMap<>(dose("PENTA", 2));
        expired.put("expiryDate", TODAY.minusDays(1).toString());
        postJson(path(encounter), expired, NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VACCINE_EXPIRED"));

        Map<String, Object> noBatch = new HashMap<>(dose("PENTA", 2));
        noBatch.remove("lotNumber");
        postJson(path(encounter), noBatch, NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BATCH_REQUIRED"));

        postJson(path(encounter), dose("PENTA", 4), NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOSE_OUT_OF_SERIES"));

        Map<String, Object> beforeBirth = new HashMap<>(Map.of("vaccineCode", "BCG", "primarySource", false,
                "reportOrigin", "Discharge card", "administeredAt", TODAY.minusWeeks(20).atStartOfDay(ZoneId.of("Asia/Kolkata"))
                        .toInstant().toString()));
        postJson(path(encounter), beforeBirth, NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BEFORE_BIRTH"));

        postJson(path(encounter), Map.of("vaccineCode", "BCG", "primarySource", false), NURSE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.originConsistent").exists());
        postJson(path(encounter), Map.of("vaccineCode", "OPV", "status", "NOT_DONE"), NURSE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reasonConsistent").exists());
        postJson(path(encounter), dose("YELLOWFEVER", 1), NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_VACCINE"));
        postJson(path(encounter), dose("PENTA", 2), RECEPTION).andExpect(status().isForbidden());
    }

    @Test
    void aWrongEntryIsMarkedInErrorNeverEdited() throws Exception {
        String encounter = openVisit(TODAY.minusWeeks(10)).get("encounterId").asText();
        JsonNode first = body(postJson(path(encounter), dose("PCV", 1), NURSE).andExpect(status().isCreated()));
        UUID id = UUID.fromString(first.path("id").asText());

        assertThatThrownBy(() -> jdbc.sql("UPDATE clinical.immunization SET lot_number = 'OTHER1' WHERE id = :id")
                .param("id", id).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM clinical.immunization WHERE id = :id").param("id", id).update())
                .hasMessageContaining("cannot be deleted");

        postJson(path(encounter) + "/" + id + "/entered-in-error", Map.of("reason", "Wrong patient selected"), NURSE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.enteredInError").value(true));
        postJson(path(encounter) + "/" + id + "/entered-in-error", Map.of("reason", "again"), NURSE)
                .andExpect(status().isConflict());
        // The dose can now be recorded correctly
        postJson(path(encounter), dose("PCV", 1), NURSE).andExpect(status().isCreated());
    }

    private static String path(String encounter) {
        return "/api/v1/emr/encounters/" + encounter + "/immunizations";
    }

    private static Map<String, Object> dose(String vaccine, int number) {
        Map<String, Object> dose = new HashMap<>();
        dose.put("vaccineCode", vaccine);
        dose.put("doseNumber", number);
        dose.put("lotNumber", "SII-" + vaccine + "-2611");
        dose.put("manufacturer", "Serum Institute of India");
        dose.put("expiryDate", TODAY.plusMonths(8).toString());
        dose.put("site", "LEFT_THIGH");
        dose.put("nextDueDate", TODAY.plusWeeks(4).toString());
        return dose;
    }

    private JsonNode openVisit(LocalDate dateOfBirth) throws Exception {
        JsonNode patient = registerPatient("F", dateOfBirth);
        JsonNode visit = body(postJson("/api/v1/opd/visits",
                Map.of("uhid", patient.get("uhid").asText(), "departmentCode", "PAED"), RECEPTION)
                .andExpect(status().isCreated()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) visit).put("uhid", patient.get("uhid").asText());
        return visit;
    }
}
