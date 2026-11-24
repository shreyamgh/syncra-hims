package com.syncra.hims.abdm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Milestone 1: creating an ABHA with Aadhaar OTP at the desk — and never keeping the Aadhaar number. */
@ExtendWith(OutputCaptureExtension.class)
class AbhaEnrolmentTest extends AbdmTestSupport {

    static final String BASE = "/api/v1/abdm/abha/enrolments";

    @Test
    void createsAnAbhaAttachesItAndNeverKeepsTheAadhaarNumber(CapturedOutput output) throws Exception {
        UUID patient = patient("Ramesh", "Kulkarni", "M", 1975, newMobile());
        ABDM.profile(new FakeAbdm.Profile("Ramesh Kulkarni", "M", 1975, null, "ACTIVE"));
        String aadhaar = newAadhaar();
        String spaced = aadhaar.substring(0, 4) + " " + aadhaar.substring(4, 8) + " " + aadhaar.substring(8);

        JsonNode started = body(postJson(BASE, Map.of("aadhaarNumber", spaced, "patientConsent", true), RECEPTION)
                .andExpect(status().isOk()));
        assertThat(started.path("aadhaar").asText()).isEqualTo("XXXX-XXXX-" + aadhaar.substring(8));
        assertThat(started.path("message").asText()).contains("******3210");
        String id = started.path("enrolmentId").asText();
        // ABHA received exactly the Aadhaar number, RSA-OAEP encrypted with its key
        assertThat(ABDM.lastAadhaar()).isEqualTo(aadhaar);
        FakeAbdm.Received otpRequest = ABDM.await("/v3/enrollment/request/otp", ABDM.count("/v3/enrollment/request/otp"));
        assertThat(otpRequest.body().path("loginId").asText()).doesNotContain(aadhaar);
        assertThat(otpRequest.body().path("scope")).extracting(JsonNode::asText).containsExactly("abha-enrol");
        assertThat(otpRequest.body().path("otpSystem").asText()).isEqualTo("aadhaar");

        postJson(BASE + "/" + id + "/verify", Map.of("otp", "000000", "mobile", FakeAbdm.AADHAAR_MOBILE), RECEPTION)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ABDM_REJECTED"));
        assertThat(outcome(id)).isEqualTo("FAILED");

        int enrolCalls = ABDM.count("/v3/enrollment/enrol/byAadhaar");
        JsonNode created = body(postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP,
                "mobile", FakeAbdm.AADHAAR_MOBILE, "patientId", patient.toString()), RECEPTION)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(created.path("isNew").asBoolean()).isTrue();
        assertThat(created.path("mobileVerificationRequired").asBoolean()).isFalse();
        assertThat(created.at("/profile/yearOfBirth").asInt()).isEqualTo(1975);
        assertThat(created.at("/profile").has("photo")).isFalse();
        assertThat(created.at("/linked/uhid").asText()).isEqualTo(uhid(patient));
        String abha = created.at("/profile/abhaNumber").asText();
        JsonNode enrol = ABDM.await("/v3/enrollment/enrol/byAadhaar", enrolCalls + 1).body();
        assertThat(enrol.at("/consent/code").asText()).isEqualTo("abha-enrollment");
        assertThat(enrol.at("/consent/version").asText()).isEqualTo("1.4");
        assertThat(enrol.at("/authData/otp/mobile").asText()).isEqualTo(FakeAbdm.AADHAAR_MOBILE);
        assertThat(outcome(id)).isEqualTo("CREATED");
        assertThat(jdbc.sql("SELECT abha_number FROM patient.patient WHERE id = :p AND abha_verified_at IS NOT NULL")
                .param("p", patient).query(String.class).single()).isEqualTo(abha);

        JsonNode suggestions = body(perform(get(BASE + "/" + id + "/address-suggestions"), RECEPTION)
                .andExpect(status().isOk()));
        assertThat(suggestions).extracting(JsonNode::asText).contains("ramesh.kulkarni1975");
        String address = "rk" + abha.substring(8);
        postJson(BASE + "/" + id + "/address", Map.of("abhaAddress", address), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abhaAddress").value(address + "@sbx"))
                .andExpect(jsonPath("$.patientUpdated").value(true));
        assertThat(jdbc.sql("SELECT abha_address FROM patient.patient WHERE id = :p").param("p", patient)
                .query(String.class).single()).isEqualTo(address + "@sbx");

        perform(get(BASE + "/" + id + "/card"), RECEPTION)
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event_log WHERE action = 'PRINT' AND entity_id = :id")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);

        // Aadhaar Act s.29: the number is in no table and no log line
        for (String table : List.of("abdm.abha_enrolment", "abdm.abha_verification", "abdm.gateway_message",
                "audit.event_log", "patient.patient", "patient.patient_identifier")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE :a")
                    .param("a", "%" + aadhaar + "%").query(Integer.class).single()).as(table).isZero();
        }
        assertThat(output.getAll()).doesNotContain(aadhaar).doesNotContain(spaced);
    }

    @Test
    void aMobileThatIsNotAadhaarLinkedIsVerifiedSeparately() throws Exception {
        ABDM.profile(new FakeAbdm.Profile("Meera Joshi", "F", 1988, null, "ACTIVE"));
        String id = start();
        String other = "9123456789";
        JsonNode created = body(postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP, "mobile", other),
                RECEPTION).andExpect(status().isOk()));
        assertThat(created.path("mobileVerificationRequired").asBoolean()).isTrue();

        int otpCalls = ABDM.count("/v3/enrollment/request/otp");
        postJson(BASE + "/" + id + "/mobile/otp", Map.of("mobile", other), RECEPTION).andExpect(status().isOk());
        JsonNode request = ABDM.await("/v3/enrollment/request/otp", otpCalls + 1).body();
        assertThat(ABDM.lastEnrolmentMobile()).isEqualTo(other);
        assertThat(request.path("loginHint").asText()).isEqualTo("mobile");
        assertThat(request.path("scope")).extracting(JsonNode::asText).containsExactly("abha-enrol", "mobile-verify");
        assertThat(request.path("txnId").asText()).startsWith("enrol-txn-");

        postJson(BASE + "/" + id + "/mobile/verify", Map.of("otp", "000000"), RECEPTION)
                .andExpect(status().isUnprocessableEntity());
        postJson(BASE + "/" + id + "/mobile/verify", Map.of("otp", FakeAbdm.VALID_OTP), RECEPTION)
                .andExpect(status().isOk()).andExpect(jsonPath("$.mobileVerified").value(true));
        assertThat(jdbc.sql("SELECT mobile_verified FROM abdm.abha_enrolment WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void anAbhaThatAlreadyExistsForTheAadhaarIsReturnedNotDuplicated() throws Exception {
        ABDM.profile(new FakeAbdm.Profile("Kiran Rao", "M", 1970, null, "ACTIVE"));
        String id = start();
        ABDM.nextEnrolmentExists(true);
        postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP, "mobile", FakeAbdm.AADHAAR_MOBILE),
                RECEPTION).andExpect(status().isOk()).andExpect(jsonPath("$.isNew").value(false));
        assertThat(outcome(id)).isEqualTo("EXISTING");
    }

    @Test
    void consentAadhaarAndOwnershipAreCheckedBeforeAnythingHappens() throws Exception {
        int otpCalls = ABDM.count("/v3/enrollment/request/otp");
        String aadhaar = newAadhaar();
        postJson(BASE, Map.of("aadhaarNumber", aadhaar, "patientConsent", false), RECEPTION)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        String typo = aadhaar.substring(0, 11) + ((aadhaar.charAt(11) - '0' + 1) % 10);
        postJson(BASE, Map.of("aadhaarNumber", typo, "patientConsent", true), RECEPTION)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AADHAAR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(typo))));
        ABDM.assertNoMore("/v3/enrollment/request/otp", otpCalls);

        String id = start();
        postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP, "mobile", FakeAbdm.AADHAAR_MOBILE),
                as("test.other", "RECEPTIONIST"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ENROLMENT_NOT_FOUND"));
        perform(get(BASE + "/" + id + "/card"), RECEPTION)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENROLMENT_STAGE"));
        postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP, "mobile", "12345"), RECEPTION)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MOBILE"));
        postJson(BASE + "/" + id + "/verify", Map.of("otp", FakeAbdm.VALID_OTP, "mobile", FakeAbdm.AADHAAR_MOBILE),
                RECEPTION).andExpect(status().isOk());
        postJson(BASE + "/" + id + "/address", Map.of("abhaAddress", "ab"), RECEPTION)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ABHA_ADDRESS"));
        postJson(BASE + "/" + id + "/address", Map.of("abhaAddress", "ram..esh1975"), RECEPTION)
                .andExpect(status().isBadRequest());
        postJson(BASE, Map.of("aadhaarNumber", newAadhaar(), "patientConsent", true), as("test.billing", "BILLING_CLERK"))
                .andExpect(status().isForbidden());
    }

    private String start() throws Exception {
        return body(postJson(BASE, Map.of("aadhaarNumber", newAadhaar(), "patientConsent", true), RECEPTION)
                .andExpect(status().isOk())).path("enrolmentId").asText();
    }

    private String outcome(String id) {
        return jdbc.sql("SELECT outcome FROM abdm.abha_enrolment WHERE id = :id").param("id", UUID.fromString(id))
                .query(String.class).single();
    }

    /** A random, checksum-valid Aadhaar-format number (not a real person's). */
    private static String newAadhaar() {
        String base = (2 + ThreadLocalRandom.current().nextInt(8))
                + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
        return base + Aadhaar.verhoeffDigit(base);
    }
}
