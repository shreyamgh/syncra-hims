package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * OPD token queue. Each test owns a department so queue assertions are not
 * affected by other tests sharing the database.
 */
class OpdApiIntegrationTest extends IntegrationTestSupport {

    @Test
    void issuesSequentialTokensWithAgeBasedPriority() throws Exception {
        JsonNode senior = registerPatient("M", LocalDate.now().minusYears(72));
        JsonNode toddler = registerPatient("F", LocalDate.now().minusYears(3));

        JsonNode first = body(visit(senior, "ENT", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("SENIOR_CITIZEN"))
                .andExpect(jsonPath("$.queueStatus").value("WAITING"))
                .andExpect(jsonPath("$.departmentCode").value("ENT")));
        JsonNode second = body(visit(toddler, "ENT", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("CHILD_UNDER_5")));
        assertThat(second.get("tokenNumber").asInt()).isEqualTo(first.get("tokenNumber").asInt() + 1);

        perform(get("/api/v1/opd/queue").param("department", "ENT"), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.WAITING").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.visits[*].uhid", Matchers.hasItems(
                        senior.get("uhid").asText(), toddler.get("uhid").asText())));

        String encounterStatus = jdbc.sql("SELECT status FROM clinical.encounter WHERE id = :id")
                .param("id", UUID.fromString(first.get("encounterId").asText())).query(String.class).single();
        assertThat(encounterStatus).isEqualTo("ARRIVED");
    }

    @Test
    void aPatientHoldsOneLiveTokenPerDepartment() throws Exception {
        JsonNode patient = registerPatient("F", LocalDate.of(1990, 4, 4));
        JsonNode token = body(visit(patient, "OPTH", null).andExpect(status().isCreated()));
        visit(patient, "OPTH", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIVE_TOKEN_EXISTS"))
                .andExpect(jsonPath("$.tokenNumber").value(token.get("tokenNumber").asInt()));
        // ...but may hold a token in another department the same day
        visit(patient, "DENT", null).andExpect(status().isCreated());
    }

    @Test
    void callNextServesEmergencyThenPriorityThenTokenOrder() throws Exception {
        JsonNode normal = registerPatient("M", LocalDate.of(1985, 1, 1));
        JsonNode senior = registerPatient("F", LocalDate.now().minusYears(66));
        JsonNode emergency = registerPatient("M", LocalDate.of(1995, 1, 1));
        visit(normal, "DERM", null).andExpect(status().isCreated());
        visit(senior, "DERM", null).andExpect(status().isCreated());
        Map<String, Object> urgent = visitBody(emergency, "DERM", null);
        urgent.put("visitType", "EMERGENCY");
        postJson("/api/v1/opd/visits", urgent, RECEPTION).andExpect(status().isCreated());

        callNext("DERM").andExpect(jsonPath("$.uhid").value(emergency.get("uhid").asText()))
                .andExpect(jsonPath("$.queueStatus").value("CALLED"));
        callNext("DERM").andExpect(jsonPath("$.uhid").value(senior.get("uhid").asText()));
        callNext("DERM").andExpect(jsonPath("$.uhid").value(normal.get("uhid").asText()));
        callNext("DERM").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUEUE_EMPTY"));
    }

    @Test
    void consultationLifecycleUpdatesTheEncounter() throws Exception {
        UUID doctorId = doctorIn("GMED");
        JsonNode patient = registerPatient("M", LocalDate.of(1979, 9, 9));
        JsonNode visit = body(visit(patient, "GMED", doctorId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctor.id").value(doctorId.toString())));
        String visitUrl = "/api/v1/opd/visits/" + visit.get("visitId").asText();

        perform(post(visitUrl + "/start"), RECEPTION).andExpect(status().isForbidden());
        perform(post(visitUrl + "/complete"), asDoctor("GMED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        perform(post(visitUrl + "/call"), RECEPTION).andExpect(jsonPath("$.queueStatus").value("CALLED"));
        perform(post(visitUrl + "/start"), asDoctor("GMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueStatus").value("IN_CONSULTATION"))
                .andExpect(jsonPath("$.consultationStartedAt").exists());
        perform(post(visitUrl + "/hold"), asDoctor("GMED")).andExpect(jsonPath("$.queueStatus").value("ON_HOLD"));
        perform(post(visitUrl + "/call"), NURSE).andExpect(jsonPath("$.queueStatus").value("CALLED"));
        perform(post(visitUrl + "/start"), asDoctor("GMED")).andExpect(status().isOk());
        perform(post(visitUrl + "/complete"), asDoctor("GMED"))
                .andExpect(jsonPath("$.queueStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());

        Map<String, Object> encounter = jdbc.sql("""
                SELECT status, attending_doctor_id, ended_at IS NOT NULL AS ended FROM clinical.encounter WHERE id = :id
                """).param("id", UUID.fromString(visit.get("encounterId").asText())).query().singleRow();
        assertThat(encounter).containsEntry("status", "FINISHED").containsEntry("attending_doctor_id", doctorId)
                .containsEntry("ended", true);

        // The token is no longer live, so the patient can be registered again today.
        visit(patient, "GMED", null).andExpect(status().isCreated());
    }

    @Test
    void validatesDepartmentAndDoctor() throws Exception {
        JsonNode patient = registerPatient("F", LocalDate.of(1981, 1, 1));
        visit(patient, "RADI", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_OPD"));
        visit(patient, "ORTH", doctorIn("GMED")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCTOR_NOT_IN_DEPARTMENT"));
        visit(patient, "XXXX", null).andExpect(status().isNotFound());

        perform(get("/api/v1/opd/departments"), RECEPTION)
                .andExpect(jsonPath("$[*].code", Matchers.hasItems("GMED", "PAED", "EMER")))
                .andExpect(jsonPath("$[*].code", Matchers.not(Matchers.hasItem("RADI"))));
        doctorIn("PAED");
        perform(get("/api/v1/opd/departments/PAED/doctors"), RECEPTION)
                .andExpect(jsonPath("$[*].name", Matchers.hasItem("Dr Test PAED Physician")));
    }

    private org.springframework.test.web.servlet.ResultActions visit(JsonNode patient, String department, UUID doctor)
            throws Exception {
        return postJson("/api/v1/opd/visits", visitBody(patient, department, doctor), RECEPTION);
    }

    private org.springframework.test.web.servlet.ResultActions callNext(String department) throws Exception {
        return perform(post("/api/v1/opd/queue/" + department + "/call-next"), NURSE);
    }

    private static Map<String, Object> visitBody(JsonNode patient, String department, UUID doctor) {
        Map<String, Object> body = new HashMap<>();
        body.put("uhid", patient.get("uhid").asText());
        body.put("departmentCode", department);
        if (doctor != null) {
            body.put("doctorId", doctor.toString());
        }
        return body;
    }
}
