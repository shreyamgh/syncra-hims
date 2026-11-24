package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Diagnoses, allergies and clinical notes: recorded, and corrected without rewriting history. */
class ClinicalEntriesApiIntegrationTest extends IntegrationTestSupport {

    @Test
    void doctorRecordsCodedDiagnosesAndCorrectsAWrongOne() throws Exception {
        doctorIn("GMED");
        String encounter = visit().get("encounterId").asText();
        String base = "/api/v1/emr/encounters/" + encounter + "/diagnoses";

        JsonNode dengue = body(postJson(base, Map.of("description", "Dengue fever", "icd10Code", "a90", "type", "FINAL",
                "primary", true), asDoctor("GMED")).andExpect(status().isCreated()));
        assertThat(dengue.path("icd10Code").asText()).isEqualTo("A90");
        postJson(base, Map.of("description", "Typhoid", "icd10Code", "A01.0", "type", "FINAL", "primary", true),
                asDoctor("GMED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This encounter already has a primary final diagnosis"));
        postJson(base, Map.of("description", "Fever", "icd10Code", "XYZ"), asDoctor("GMED"))
                .andExpect(status().isBadRequest());
        postJson(base, Map.of("description", "Fever"), NURSE).andExpect(status().isForbidden());

        postJson(base + "/" + dengue.path("id").asText() + "/entered-in-error", Map.of(), asDoctor("GMED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enteredInError").value(true));
        postJson(base, Map.of("description", "Typhoid fever", "icd10Code", "A01.0", "type", "FINAL", "primary", true),
                asDoctor("GMED")).andExpect(status().isCreated());
        perform(get("/api/v1/emr/encounters/" + encounter), NURSE)
                .andExpect(jsonPath("$.diagnoses.length()").value(2));
    }

    @Test
    void callerSeesTheirOwnStaffRecord() throws Exception {
        doctorIn("GMED");
        perform(get("/api/v1/emr/me"), asDoctor("GMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.doctor").value(true))
                .andExpect(jsonPath("$.staffId").isNotEmpty());
        perform(get("/api/v1/emr/me"), as("test.unlinked", "NURSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false))
                .andExpect(jsonPath("$.staffId").doesNotExist());
    }

    @Test
    void allergiesAreRecordedOnceAndEndedByStatus() throws Exception {
        String uhid = registerPatient("F", LocalDate.of(1980, 1, 1)).get("uhid").asText();
        String base = "/api/v1/emr/patients/" + uhid + "/allergies";
        JsonNode penicillin = body(postJson(base, Map.of("allergenType", "DRUG_CLASS", "allergenText", "Penicillin",
                "drugClass", "Penicillins", "reaction", "Urticaria", "severity", "MODERATE"), NURSE)
                .andExpect(status().isCreated()));
        postJson(base, Map.of("allergenType", "DRUG_CLASS", "allergenText", "penicillin", "drugClass", "Penicillins"), NURSE)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLERGY_EXISTS"));
        postJson(base, Map.of("allergenType", "DRUG_CLASS", "allergenText", "Sulpha"), NURSE)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRUG_CLASS_REQUIRED"));
        postJson(base, Map.of("allergenType", "FOOD", "allergenText", "Peanuts"), RECEPTION).andExpect(status().isForbidden());

        perform(put(base + "/" + penicillin.path("id").asText() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"REFUTED\"}"), NURSE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUTED"));
        assertThat(body(perform(get(base), NURSE).andExpect(status().isOk())).get(0).path("status").asText())
                .isEqualTo("REFUTED");
    }

    @Test
    void notesAreDraftedSignedAndAmendedButNeverRewritten() throws Exception {
        doctorIn("GMED");
        staffMember("test.nurse", "NURSE", "Sister Test");
        String encounter = visit().get("encounterId").asText();
        String base = "/api/v1/emr/encounters/" + encounter + "/notes";
        RequestPostProcessor doctor = asDoctor("GMED");

        postJson(base, Map.of("noteType", "DISCHARGE_SUMMARY", "content", "x"), NURSE)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DOCTOR_NOTE"));
        postJson(base, Map.of("noteType", "NURSING", "content", "Vitals stable, oral fluids encouraged", "sign", true), NURSE)
                .andExpect(status().isCreated());
        postJson(base, Map.of("noteType", "RADIOLOGY_REPORT", "content", "x"), doctor)
                .andExpect(status().isUnprocessableEntity());

        JsonNode draft = body(postJson(base, Map.of("noteType", "EXAMINATION", "content", "Chest clear."), doctor)
                .andExpect(status().isCreated()));
        String noteUrl = base + "/" + draft.path("id").asText();
        perform(put(noteUrl).header("If-Match", "\"" + draft.path("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Chest clear. Abdomen soft.\"}"), doctor)
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Chest clear. Abdomen soft."));
        perform(put(noteUrl).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"stale\"}"), doctor).andExpect(status().isPreconditionFailed());
        perform(post(noteUrl + "/sign"), NURSE).andExpect(status().isForbidden());
        perform(post(noteUrl + "/sign"), doctor).andExpect(status().isOk());
        perform(put(noteUrl).header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"changed\"}"), doctor)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_SIGNED"));

        postJson(base, Map.of("noteType", "EXAMINATION", "content", "Correction: mild hepatomegaly.",
                "amendsNoteId", draft.path("id").asText()), doctor)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amendsNoteId").value(draft.path("id").asText()));
        perform(get("/api/v1/emr/encounters/" + encounter), NURSE)
                .andExpect(jsonPath("$.notes.length()").value(3));
    }

    private JsonNode visit() throws Exception {
        JsonNode patient = registerPatient("M", LocalDate.of(1975, 5, 5));
        return body(postJson("/api/v1/opd/visits",
                Map.of("uhid", patient.get("uhid").asText(), "departmentCode", "GMED"), RECEPTION)
                .andExpect(status().isCreated()));
    }
}
