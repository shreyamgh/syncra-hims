package com.syncra.hims.abdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Milestone 1: ABHA verification by OTP, and Scan & Share at the registration desk. */
class AbhaMilestoneOneTest extends AbdmTestSupport {

    @Test
    void otpVerificationAttachesTheVerifiedAbhaToThePatient() throws Exception {
        UUID patient = patient("Ramesh", "M", 1975);
        String abha = newAbhaNumber();
        String hyphenated = abha.substring(0, 2) + "-" + abha.substring(2, 6) + "-" + abha.substring(6, 10) + "-"
                + abha.substring(10);
        ABDM.profile(new FakeAbdm.Profile("Ramesh Kulkarni", "M", 1975, "ramesh." + abha.substring(10) + "@sbx", "ACTIVE"));
        int otpCalls = ABDM.count("/v3/profile/login/request/otp");

        JsonNode otp = body(postJson("/api/v1/abdm/abha/otp", Map.of("method", "AADHAAR_OTP", "abhaNumber", hyphenated),
                RECEPTION).andExpect(status().isOk()));
        assertThat(otp.path("abhaNumber").asText()).isEqualTo(abha.substring(0, 2) + "-XXXX-XXXX-" + abha.substring(10));

        FakeAbdm.Received request = ABDM.await("/v3/profile/login/request/otp", otpCalls + 1);
        // The ABHA number travels RSA-OAEP encrypted with ABHA's published key
        assertThat(ABDM.decryptForAbha(request.body().path("loginId").asText())).isEqualTo(hyphenated);
        assertThat(request.body().path("scope")).extracting(JsonNode::asText).containsExactly("abha-login", "aadhaar-verify");
        assertThat(request.body().path("otpSystem").asText()).isEqualTo("aadhaar");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + FakeAbdm.SESSION_TOKEN);
        assertThat(UUID.fromString(request.header("REQUEST-ID"))).isNotNull();
        assertThat(request.header("TIMESTAMP")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(request.header("X-CM-ID")).isEqualTo("sbx");

        Map<String, Object> wrongOtp = Map.of("method", "AADHAAR_OTP", "txnId", otp.path("txnId").asText(),
                "otp", "000000", "abhaNumber", abha, "patientId", patient.toString());
        postJson("/api/v1/abdm/abha/verify", wrongOtp, RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ABDM_REJECTED"))
                .andExpect(jsonPath("$.abdmCode").value("ABDM-1204"));
        assertThat(outcomes(abha)).containsExactly("FAILED");

        Map<String, Object> rightOtp = Map.of("method", "AADHAAR_OTP", "txnId", otp.path("txnId").asText(),
                "otp", FakeAbdm.VALID_OTP, "abhaNumber", abha, "patientId", patient.toString());
        JsonNode verified = body(postJson("/api/v1/abdm/abha/verify", rightOtp, RECEPTION).andExpect(status().isOk()));
        assertThat(verified.at("/profile/name").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(verified.at("/profile/yearOfBirth").asInt()).isEqualTo(1975);
        assertThat(verified.at("/profile").has("profilePhoto")).isFalse();
        assertThat(verified.at("/linked/uhid").asText()).isEqualTo(uhid(patient));

        Map<String, Object> row = jdbc.sql("SELECT abha_number, abha_address, abha_verified_at IS NOT NULL AS verified, "
                + "version FROM patient.patient WHERE id = :id").param("id", patient).query().singleRow();
        assertThat(row).containsEntry("abha_number", abha).containsEntry("verified", true)
                .containsEntry("abha_address", "ramesh." + abha.substring(10) + "@sbx");
        assertThat((Long) row.get("version")).isEqualTo(1L);
        assertThat(outcomes(abha)).containsExactlyInAnyOrder("FAILED", "SUCCESS");
    }

    @Test
    void abhaOfSomeoneElseIsNotAttached() throws Exception {
        UUID patient = patient("Sunita", "F", 1990);
        String abha = newAbhaNumber();
        ABDM.profile(new FakeAbdm.Profile("Ramesh Kulkarni", "M", 1975, "r" + abha.substring(8) + "@sbx", "ACTIVE"));
        JsonNode otp = body(postJson("/api/v1/abdm/abha/otp", Map.of("method", "MOBILE_OTP", "abhaNumber", abha),
                RECEPTION).andExpect(status().isOk()));

        postJson("/api/v1/abdm/abha/verify", Map.of("method", "MOBILE_OTP", "txnId", otp.path("txnId").asText(),
                "otp", FakeAbdm.VALID_OTP, "abhaNumber", abha, "patientId", patient.toString()), RECEPTION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMOGRAPHIC_MISMATCH"))
                .andExpect(jsonPath("$.fields[0]").value("gender"))
                .andExpect(jsonPath("$.fields[1]").value("year of birth"));
        assertThat(jdbc.sql("SELECT abha_number IS NULL AND abha_verified_at IS NULL FROM patient.patient WHERE id = :id")
                .param("id", patient).query(Boolean.class).single()).isTrue();
    }

    @Test
    void verificationCanOnlyBeUsedByWhoeverMadeIt() throws Exception {
        String abha = newAbhaNumber();
        ABDM.profile(new FakeAbdm.Profile("Ramesh Kulkarni", "M", 1975, "rk" + abha.substring(8) + "@sbx", "ACTIVE"));
        JsonNode otp = body(postJson("/api/v1/abdm/abha/otp", Map.of("method", "AADHAAR_OTP", "abhaNumber", abha),
                RECEPTION).andExpect(status().isOk()));
        JsonNode verified = body(postJson("/api/v1/abdm/abha/verify", Map.of("method", "AADHAAR_OTP",
                "txnId", otp.path("txnId").asText(), "otp", FakeAbdm.VALID_OTP, "abhaNumber", abha), RECEPTION)
                .andExpect(status().isOk()));
        String verificationId = verified.path("verificationId").asText();

        // Registered afterwards (new patient), then linked by the same receptionist
        UUID patient = patient("Ramesh", "M", 1975);
        postJson("/api/v1/abdm/patients/" + patient + "/abha", Map.of("verificationId", verificationId),
                as("test.other", "RECEPTIONIST")).andExpect(status().isForbidden());
        postJson("/api/v1/abdm/patients/" + patient + "/abha", Map.of("verificationId", verificationId), RECEPTION)
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaNumber").value(abha));
        // ... and only for that patient
        UUID other = patient("Ramesh", "M", 1975);
        postJson("/api/v1/abdm/patients/" + other + "/abha", Map.of("verificationId", verificationId), RECEPTION)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERIFICATION_USED"));
    }

    @Test
    void abhaApisAreForDeskStaffOnly() throws Exception {
        postJson("/api/v1/abdm/abha/otp", Map.of("method", "AADHAAR_OTP", "abhaNumber", newAbhaNumber()),
                as("test.billing", "BILLING_CLERK")).andExpect(status().isForbidden());
        postJson("/api/v1/abdm/abha/otp", Map.of("method", "AADHAAR_OTP", "abhaNumber", "12345"), RECEPTION)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ABHA_NUMBER"));
    }

    @Test
    void scanAndShareGivesAQueueNumberAndRegistersThePatientWithAVerifiedAbha() throws Exception {
        String abha = newAbhaNumber();
        String address = "share." + abha.substring(8) + "@sbx";
        String counter = "OPD-" + abha.substring(10);
        Map<String, Object> share = Map.of(
                "intent", "PROFILE_SHARE",
                "metaData", Map.of("hipId", HIP_ID, "context", counter),
                "profile", Map.of("patient", Map.of(
                        "abhaNumber", abha.substring(0, 2) + "-" + abha.substring(2, 6) + "-" + abha.substring(6, 10)
                                + "-" + abha.substring(10),
                        "abhaAddress", address, "name", "Meera Joshi", "gender", "F",
                        "yearOfBirth", "1988", "monthOfBirth", "3", "dayOfBirth", "14", "phoneNumber", "+91-9822012345",
                        "address", Map.of("line", "Flat 4, Kothrud", "district", "Pune", "state", "Maharashtra",
                                "pincode", "411038"))));
        int replies = ABDM.count("/patient-share/v3/on-share");

        UUID requestId = UUID.randomUUID();
        callback("/api/v3/hip/patient/share", requestId, share).andExpect(status().isAccepted());
        FakeAbdm.Received reply = ABDM.await("/patient-share/v3/on-share", replies + 1);
        assertThat(reply.body().at("/response/requestId").asText()).isEqualTo(requestId.toString());
        assertThat(reply.body().at("/acknowledgement/status").asText()).isEqualTo("SUCCESS");
        assertThat(reply.body().at("/acknowledgement/profile/tokenNumber").asText()).isEqualTo("1");
        assertThat(reply.body().at("/acknowledgement/profile/context").asText()).isEqualTo(counter);
        assertThat(reply.header("X-HIP-ID")).isEqualTo(HIP_ID);

        // ABDM retries the same callback: answered already, nothing new
        callback("/api/v3/hip/patient/share", requestId, share).andExpect(status().isAccepted());
        ABDM.assertNoMore("/patient-share/v3/on-share", replies + 1);
        // The patient shares again from the app: same queue number
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), share).andExpect(status().isAccepted());
        assertThat(ABDM.await("/patient-share/v3/on-share", replies + 2).body()
                .at("/acknowledgement/profile/tokenNumber").asText()).isEqualTo("1");

        JsonNode queue = body(perform(get("/api/v1/abdm/scan-share").param("context", counter), RECEPTION)
                .andExpect(status().isOk()));
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).path("name").asText()).isEqualTo("Meera Joshi");
        assertThat(queue.get(0).path("mobile").asText()).isEqualTo("9822012345");

        UUID patient = jdbc.sql("""
                INSERT INTO patient.patient (first_name, last_name, gender, date_of_birth, registration_source)
                VALUES ('Meera', 'Joshi', 'F', DATE '1988-03-14', 'ABHA_SCAN_SHARE') RETURNING id
                """).query(UUID.class).single();
        postJson("/api/v1/abdm/scan-share/" + queue.get(0).path("id").asText() + "/register",
                Map.of("patientId", patient.toString()), RECEPTION)
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaAddress").value(address));
        assertThat(jdbc.sql("SELECT method FROM abdm.abha_verification WHERE patient_id = :id").param("id", patient)
                .query(String.class).list()).containsExactly("QR_SCAN");
        assertThat(body(perform(get("/api/v1/abdm/scan-share").param("context", counter), RECEPTION)
                .andExpect(status().isOk()))).isEmpty();
    }

    private List<String> outcomes(String abha) {
        return jdbc.sql("SELECT outcome FROM abdm.abha_verification WHERE abha_number = :n ORDER BY performed_at")
                .param("n", abha).query(String.class).list();
    }
}
