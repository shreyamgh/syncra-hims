package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class PatientApiIntegrationTest extends IntegrationTestSupport {

    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.BASIC_ISO_DATE);

    @Test
    void registersPatientWithDatabaseIssuedUhid() throws Exception {
        Map<String, Object> request = patientBody("Ramesh", "Kumar", "M", LocalDate.of(1980, 5, 1), randomMobile());
        request.put("abhaNumber", "91-" + (1000 + (int) (Math.random() * 8999)) + "-5678-9012");
        request.put("address", Map.of("line", "12 MG Road", "district", "Pune", "state", "Maharashtra",
                "pincode", "411001"));

        JsonNode body = body(postJson("/api/v1/patients?confirmNotDuplicate=true", request, RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.fullName").value("Ramesh Kumar"))
                .andExpect(jsonPath("$.abhaNumber").value(org.hamcrest.Matchers.matchesPattern("^\\d{2}-\\d{4}-\\d{4}-\\d{4}$")))
                .andExpect(jsonPath("$.address.pincode").value("411001"))
                .andExpect(jsonPath("$.registrationSource").value("WALK_IN")));

        String uhid = body.get("uhid").asText();
        assertThat(uhid).matches("^SYNC-" + TODAY + "-\\d{4}$");
        assertThat(body.get("ageYears").asInt()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void uhidsAreSequentialWithinTheDay() throws Exception {
        int first = serial(registerPatient("F", LocalDate.of(1992, 1, 1)).get("uhid").asText());
        int second = serial(registerPatient("M", LocalDate.of(1993, 1, 1)).get("uhid").asText());
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void estimatesDateOfBirthFromAge() throws Exception {
        Map<String, Object> request = patientBody(uniqueName("Aged"), "Person", "F", null, randomMobile());
        request.put("ageYears", 30);
        postJson("/api/v1/patients?confirmNotDuplicate=true", request, RECEPTION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dobEstimated").value(true))
                .andExpect(jsonPath("$.ageYears").value(30));
    }

    @Test
    void rejectsInvalidPayloadsWithFieldErrors() throws Exception {
        Map<String, Object> request = patientBody("", "X", "M", LocalDate.of(1990, 1, 1), "12345");
        request.put("ageYears", 34);
        postJson("/api/v1/patients", request, RECEPTION)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.firstName").exists())
                .andExpect(jsonPath("$.fields.mobile").exists())
                .andExpect(jsonPath("$.fields.singleAgeSource").exists());

        Map<String, Object> noAge = patientBody("No", "Age", "M", null, randomMobile());
        postJson("/api/v1/patients", noAge, RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_REQUIRED"));
    }

    @Test
    void flagsProbableDuplicatesUnlessConfirmed() throws Exception {
        String mobile = randomMobile();
        String surname = uniqueName("Sharma");
        JsonNode existing = body(postJson("/api/v1/patients",
                patientBody("Kavita", surname, "F", LocalDate.of(1985, 3, 12), mobile), RECEPTION)
                .andExpect(status().isCreated()));

        Map<String, Object> similar = patientBody("Kavitha", surname, "F", LocalDate.of(1985, 3, 12), mobile);
        postJson("/api/v1/patients", similar, RECEPTION)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.candidates[0].uhid").value(existing.get("uhid").asText()))
                .andExpect(jsonPath("$.candidates[0].maskedMobile").value("******" + mobile.substring(6)));

        postJson("/api/v1/patients?confirmNotDuplicate=true", similar, RECEPTION)
                .andExpect(status().isCreated());
    }

    @Test
    void abhaNumberCanBelongToOnlyOnePatient() throws Exception {
        String abha = "14" + String.format("%012d", (long) (Math.random() * 1e12));
        Map<String, Object> first = patientBody(uniqueName("Abha"), "One", "M", LocalDate.of(1970, 1, 1), randomMobile());
        first.put("abhaNumber", abha);
        postJson("/api/v1/patients?confirmNotDuplicate=true", first, RECEPTION).andExpect(status().isCreated());

        Map<String, Object> second = patientBody(uniqueName("Abha"), "Two", "F", LocalDate.of(1971, 1, 1), randomMobile());
        second.put("abhaNumber", abha);
        postJson("/api/v1/patients?confirmNotDuplicate=true", second, RECEPTION)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.detail").value("This ABHA number is already linked to another patient"));
    }

    @Test
    void findsPatientsByNameMobileAndUhidWithMaskedMobiles() throws Exception {
        String mobile = randomMobile();
        String firstName = uniqueName("Meenakshi");
        JsonNode created = body(postJson("/api/v1/patients?confirmNotDuplicate=true",
                patientBody(firstName, "Iyer", "F", LocalDate.of(1966, 8, 20), mobile), RECEPTION));
        String uhid = created.get("uhid").asText();

        perform(get("/api/v1/patients").param("q", firstName.substring(0, 7)), NURSE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uhid == '" + uhid + "')].maskedMobile").value("******" + mobile.substring(6)));
        perform(get("/api/v1/patients").param("q", mobile), NURSE)
                .andExpect(jsonPath("$[0].uhid").value(uhid));
        perform(get("/api/v1/patients").param("q", uhid.toLowerCase()), NURSE)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value(firstName + " Iyer"));
        perform(get("/api/v1/patients").param("q", "ab"), NURSE)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updatesRequireTheCurrentETag() throws Exception {
        JsonNode created = registerPatient("M", LocalDate.of(1975, 6, 15));
        String uhid = created.get("uhid").asText();
        Map<String, Object> changed = patientBody(created.get("firstName").asText(), "Patient", "M",
                LocalDate.of(1975, 6, 15), randomMobile());
        changed.put("bloodGroup", "B+");

        perform(put("/api/v1/patients/" + uhid).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(changed)), RECEPTION)
                .andExpect(status().isPreconditionRequired());
        perform(put("/api/v1/patients/" + uhid).header(HttpHeaders.IF_MATCH, "\"7\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)), RECEPTION)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        perform(put("/api/v1/patients/" + uhid).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(changed)), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.bloodGroup").value("B+"));

        String fields = jdbc.sql("""
                SELECT details->>'fields' FROM audit.event_log
                WHERE entity_type = 'patient' AND entity_id = :uhid AND action = 'UPDATE'
                """).param("uhid", uhid).query(String.class).single();
        assertThat(fields).contains("bloodGroup", "mobile").doesNotContain("9");
    }

    @Test
    void readsAndWritesAreAudited() throws Exception {
        JsonNode created = registerPatient("F", LocalDate.of(1988, 2, 2));
        UUID patientId = UUID.fromString(created.get("id").asText());
        perform(get("/api/v1/patients/" + created.get("uhid").asText())
                .header("X-Request-Id", "audit-test-42"), NURSE)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""));

        Map<String, Object> read = jdbc.sql("""
                SELECT actor, actor_role, request_id, service FROM audit.event_log
                WHERE patient_id = :patient AND action = 'READ'
                """).param("patient", patientId).query().singleRow();
        assertThat(read).containsEntry("actor", "test.nurse").containsEntry("actor_role", "NURSE")
                .containsEntry("request_id", "audit-test-42").containsEntry("service", "patient-service");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event_log WHERE patient_id = :p AND action = 'CREATE'")
                .param("p", patientId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void enforcesAuthenticationAndRoles() throws Exception {
        mvc.perform(get("/api/v1/patients/SYNC-20261120-0001")).andExpect(status().isUnauthorized());
        postJson("/api/v1/patients", patientBody("Bill", "Clerk", "M", LocalDate.of(1990, 1, 1), randomMobile()),
                BILLING)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        perform(get("/api/v1/patients/not-a-uhid"), NURSE)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_UHID"));
        perform(get("/api/v1/patients/SYNC-19990101-9999"), NURSE)
                .andExpect(status().isNotFound());
    }

    private static int serial(String uhid) {
        return Integer.parseInt(uhid.substring(uhid.length() - 4));
    }
}
