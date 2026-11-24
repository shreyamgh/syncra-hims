package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Doctor Desk record: triage vitals and the signed consultation with dictation provenance. */
class EmrApiIntegrationTest extends IntegrationTestSupport {

    @Test
    void nurseRecordsTriageVitalsAndDoctorSeesThem() throws Exception {
        String encounter = openVisit("PAED").get("encounterId").asText();
        postJson("/api/v1/emr/encounters/" + encounter + "/vitals",
                Map.of("temperatureC", 38.4, "pulseBpm", 112, "weightKg", 18.5, "heightCm", 110), NURSE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latestVitals.pulseBpm").value(112))
                .andExpect(jsonPath("$.latestVitals.bmi").value(15.29))
                .andExpect(jsonPath("$.latestVitals.source").value("MANUAL"));

        perform(get("/api/v1/emr/encounters/" + encounter), asDoctor("PAED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vitalsHistory.length()").value(1))
                .andExpect(jsonPath("$.visit.queueStatus").value("WAITING"));

        postJson("/api/v1/emr/encounters/" + encounter + "/vitals", Map.of("bpSystolic", 80, "bpDiastolic", 120), NURSE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.bloodPressureConsistent").exists());
        postJson("/api/v1/emr/encounters/" + encounter + "/vitals", Map.of("pulseBpm", 80), RECEPTION)
                .andExpect(status().isForbidden());
    }

    @Test
    void signedConsultationSavesDictatedRowsWithProvenanceAndCompletesTheVisit() throws Exception {
        doctorIn("GSUR");
        JsonNode visit = openVisit("GSUR");
        String encounter = visit.get("encounterId").asText();
        String session = "sess" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> request = new HashMap<>();
        request.put("dictationSessions", List.of(Map.of(
                "aiSessionId", session, "language", "en-IN", "engine", "whisper-small",
                "audioSeconds", 41.5, "outcome", "completed",
                "startedAt", Instant.now().minusSeconds(60).toString(), "endedAt", Instant.now().toString())));
        request.put("vitals", Map.of("bpSystolic", 130, "bpDiastolic", 80, "pulseBpm", 96,
                "source", "DICTATION", "dictationSessionRef", session));
        request.put("complaints", List.of(
                Map.of("description", "Fever", "durationValue", 3, "durationUnit", "DAYS", "chief", true,
                        "source", "DICTATION", "dictationSessionRef", session),
                Map.of("description", "Body ache", "source", "MANUAL")));
        request.put("prescription", Map.of(
                "advice", "Plenty of oral fluids",
                "followUpDate", LocalDate.now().plusDays(5).toString(),
                "items", List.of(
                        Map.of("drugText", "Tab Paracetamol 650 mg", "dose", "1 tab", "route", "ORAL",
                                "frequency", "BD", "timing", "AFTER_FOOD", "durationDays", 5,
                                "source", "DICTATION", "dictationSessionRef", session),
                        Map.of("drugText", "Cap Omeprazole 20 mg", "frequency", "OD", "timing", "BEFORE_FOOD",
                                "durationDays", 5))));
        request.put("completeVisit", true);

        postJson("/api/v1/emr/encounters/" + encounter + "/consultation", request, asDoctor("GSUR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.visit.queueStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.latestVitals.source").value("DICTATION"))
                .andExpect(jsonPath("$.complaints[0].description").value("Fever"))
                .andExpect(jsonPath("$.complaints[0].chief").value(true))
                .andExpect(jsonPath("$.prescriptions[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.prescriptions[0].prescribedBy").value("Dr Test GSUR Physician"))
                .andExpect(jsonPath("$.prescriptions[0].items.length()").value(2))
                .andExpect(jsonPath("$.prescriptions[0].items[0].source").value("DICTATION"))
                .andExpect(jsonPath("$.prescriptions[0].items[1].source").value("MANUAL"));

        Long linked = jdbc.sql("""
                SELECT count(*) FROM clinical.prescription_item i
                JOIN clinical.dictation_session s ON s.id = i.dictation_session_id
                WHERE s.ai_session_id = :session
                """).param("session", session).query(Long.class).single();
        assertThat(linked).isEqualTo(1L);

        // The encounter is closed: nothing more can be signed into it.
        postJson("/api/v1/emr/encounters/" + encounter + "/consultation", Map.of("completeVisit", false),
                asDoctor("GSUR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCOUNTER_CLOSED"));
    }

    @Test
    void dictatedRowsMustReferenceASessionAndTheWholeSaveRollsBack() throws Exception {
        doctorIn("ORTH");
        String encounter = openVisit("ORTH").get("encounterId").asText();
        Map<String, Object> request = Map.of(
                "complaints", List.of(Map.of("description", "Knee pain", "source", "MANUAL")),
                "prescription", Map.of("items", List.of(Map.of("drugText", "Tab Aceclofenac 100 mg",
                        "source", "DICTATION", "dictationSessionRef", "missing"))),
                "completeVisit", true);

        postJson("/api/v1/emr/encounters/" + encounter + "/consultation", request, asDoctor("ORTH"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DICTATION_SESSION_UNKNOWN"));
        perform(get("/api/v1/emr/encounters/" + encounter), asDoctor("ORTH"))
                .andExpect(jsonPath("$.complaints.length()").value(0))
                .andExpect(jsonPath("$.visit.queueStatus").value("WAITING"));
    }

    @Test
    void onlyDoctorsWithAStaffRecordCanSign() throws Exception {
        String encounter = openVisit("DENT").get("encounterId").asText();
        Map<String, Object> request = Map.of("prescription",
                Map.of("items", List.of(Map.of("drugText", "Tab Amoxicillin 500 mg", "frequency", "TDS"))));

        postJson("/api/v1/emr/encounters/" + encounter + "/consultation", request, NURSE)
                .andExpect(status().isForbidden());
        postJson("/api/v1/emr/encounters/" + encounter + "/consultation", request, as("locum.unknown", "DOCTOR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_DOCTOR_RECORD"));
        postJson("/api/v1/emr/encounters/" + encounter + "/consultation",
                Map.of("prescription", Map.of("items", List.of(Map.of("drugText", "X", "frequency", "HOURLY")))),
                asDoctor("DENT"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode openVisit(String department) throws Exception {
        JsonNode patient = registerPatient("M", LocalDate.of(2018, 6, 1));
        return body(postJson("/api/v1/opd/visits",
                Map.of("uhid", patient.get("uhid").asText(), "departmentCode", department), RECEPTION)
                .andExpect(status().isCreated()));
    }
}
