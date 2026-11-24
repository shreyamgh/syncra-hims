package com.syncra.hims.abdm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Milestone 2, patient-initiated linking: discovery from the PHR app, an OTP by SMS to the
 * registered mobile, and confirmation — against the simulated gateway and SMS provider.
 */
class PatientInitiatedLinkingTest extends AbdmTestSupport {

    static final Instant VISIT = Instant.parse("2026-11-05T05:00:00Z");
    static final String ON_DISCOVER = "/patient/care-context/on-discover";
    static final String ON_INIT = "/link/care-context/on-init";
    static final String ON_CONFIRM = "/link/care-context/on-confirm";
    static final String SMS = "/sms/send";

    @Test
    void aPatientFindsAndLinksTheirRecordsFromThePhrApp() throws Exception {
        String mobile = newMobile();
        UUID patient = patient("Sunil", "Patil", "M", 1982, mobile);
        UUID first = opdConsultation(patient, VISIT);
        UUID second = opdConsultation(patient, VISIT.plusSeconds(7 * 86_400));
        String address = "sunil." + mobile.substring(6) + "@sbx";
        UUID transaction = UUID.randomUUID();

        JsonNode discovered = discover(transaction, address, "Sunil Patil", "M", 1982, mobile, uhid(patient));
        assertThat(discovered.path("transactionId").asText()).isEqualTo(transaction.toString());
        assertThat(discovered.path("matchedBy")).extracting(JsonNode::asText).containsExactly("MOBILE", "MR");
        assertThat(discovered.path("patient")).as("one group per record type").extracting(g -> g.path("hiType").asText())
                .containsExactly("OPConsultation", "Prescription", "WellnessRecord");
        JsonNode group = discovered.path("patient").get(0);
        assertThat(group.path("referenceNumber").asText()).isEqualTo(uhid(patient));
        assertThat(group.path("display").asText()).isEqualTo("Sunil Patil");
        assertThat(group.path("count").asInt()).isEqualTo(2);
        assertThat(references(discovered)).containsExactlyInAnyOrder(first.toString(), second.toString());

        int sms = ABDM.count(SMS);
        JsonNode init = linkInit(transaction, address, uhid(patient), List.of(first.toString(), second.toString()));
        String linkRef = init.at("/link/referenceNumber").asText();
        assertThat(init.at("/link/authenticationType").asText()).isEqualTo("DIRECT");
        assertThat(init.at("/link/meta/communicationMedium").asText()).isEqualTo("MOBILE");
        assertThat(init.at("/link/meta/communicationHint").asText()).isEqualTo("******" + mobile.substring(6));

        FakeAbdm.Received message = ABDM.await(SMS, sms + 1);
        assertThat(message.body().path("to").asText()).isEqualTo("91" + mobile);
        assertThat(message.body().path("senderId").asText()).isEqualTo("SYNCRA");
        assertThat(message.body().path("templateId").asText()).isEqualTo("1207170000000000001");
        String otp = message.body().at("/variables/otp").asText();
        assertThat(otp).matches("\\d{6}");
        assertThat(message.body().path("message").asText()).startsWith(otp + " is the OTP to link your Syncra Test Hospital");
        Map<String, Object> stored = jdbc.sql("SELECT otp_salt, otp_hash FROM abdm.link_session WHERE link_reference = :r")
                .param("r", linkRef).query().singleRow();
        assertThat(stored.values()).as("the OTP itself is never stored").noneMatch(v -> v.toString().contains(otp));

        JsonNode wrong = confirm(linkRef, otp.equals("000000") ? "111111" : "000000");
        assertThat(wrong.at("/error/code").asInt()).isEqualTo(1401);
        assertThat(wrong.at("/error/message").asText()).isEqualTo("Wrong OTP");

        JsonNode confirmed = confirm(linkRef, otp);
        assertThat(confirmed.has("error")).isFalse();
        assertThat(references(confirmed)).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(jdbc.sql("SELECT DISTINCT link_status || ' ' || linked_abha_address FROM abdm.care_context WHERE patient_id = :p")
                .param("p", patient).query(String.class).list()).containsExactly("LINKED " + address);
        assertThat(jdbc.sql("SELECT status FROM abdm.link_session WHERE link_reference = :r").param("r", linkRef)
                .query(String.class).single()).isEqualTo("LINKED");

        // Searching again: nothing left to link
        JsonNode again = discover(UUID.randomUUID(), address, "Sunil Patil", "M", 1982, mobile, null);
        assertThat(again.at("/error/message").asText()).isEqualTo("No records left to link at this hospital");
    }

    @Test
    void discoveryNeedsStrongEvidenceAndExactlyOnePatient() throws Exception {
        String mobile = newMobile();
        UUID elder = patient("Anita", "Joshi", "F", 1990, mobile);
        UUID sister = patient("Anita", "Joshi", "F", 1990, mobile); // same family phone, same name and year
        opdConsultation(elder, VISIT);
        opdConsultation(sister, VISIT);
        String address = "anita." + mobile.substring(6) + "@sbx";

        assertThat(discover(UUID.randomUUID(), address, "Anita Joshi", "F", 1990, mobile, null).at("/error/code").asInt())
                .as("ambiguous without the UHID").isEqualTo(1409);
        JsonNode narrowed = discover(UUID.randomUUID(), address, "Anita Joshi", "F", 1990, mobile, uhid(sister));
        assertThat(narrowed.at("/patient/0/referenceNumber").asText()).isEqualTo(uhid(sister));

        assertThat(discover(UUID.randomUUID(), address, "Anita Joshi", "F", 1991, mobile, uhid(sister))
                .at("/error/message").asText()).isEqualTo("No matching patient found at this hospital");
        assertThat(discover(UUID.randomUUID(), address, "Anita Joshi", "M", 1990, mobile, uhid(sister))
                .at("/error/code").asInt()).isEqualTo(1404);
        assertThat(discover(UUID.randomUUID(), address, "Anita Joshi", "F", 1990, newMobile(), uhid(sister))
                .at("/error/code").asInt()).as("UHID alone is not enough").isEqualTo(1404);

        // A patient who already holds a different verified ABHA is never offered to someone else
        verifiedAbha(elder, newAbhaNumber(), "anita.real." + mobile.substring(6) + "@sbx");
        JsonNode other = discover(UUID.randomUUID(), "imposter." + mobile.substring(6) + "@sbx", "Anita Joshi", "F", 1990,
                mobile, uhid(elder));
        assertThat(other.has("error")).isFalse();
        assertThat(other.at("/patient/0/referenceNumber").asText()).as("only the sister remains").isEqualTo(uhid(sister));
        // ... and that verified ABHA finds its own patient directly
        JsonNode own = discover(UUID.randomUUID(), "anita.real." + mobile.substring(6) + "@sbx", "A Joshi", "F", 1990,
                null, null);
        assertThat(own.at("/patient/0/referenceNumber").asText()).isEqualTo(uhid(elder));
        assertThat(own.path("matchedBy")).extracting(JsonNode::asText).containsExactly("ABHA_ADDRESS");
    }

    @Test
    void onlyWhatTheDiscoveryOfferedCanBeLinked() throws Exception {
        String mobile = newMobile();
        UUID patient = patient("Kiran", "Rao", "M", 1970, mobile);
        UUID encounter = opdConsultation(patient, VISIT);
        UUID stranger = patient("Mohan", "Das", "M", 1960, newMobile());
        UUID strangersVisit = opdConsultation(stranger, VISIT);
        String address = "kiran." + mobile.substring(6) + "@sbx";
        UUID transaction = UUID.randomUUID();
        discover(transaction, address, "Kiran Rao", "M", 1970, mobile, null);
        int sms = ABDM.count(SMS);

        JsonNode foreign = linkInit(transaction, address, uhid(patient), List.of(strangersVisit.toString()));
        assertThat(foreign.at("/error/message").asText()).isEqualTo("Only care contexts offered in this discovery can be linked");
        JsonNode otherAbha = linkInit(transaction, "someone.else@sbx", uhid(patient), List.of(encounter.toString()));
        assertThat(otherAbha.at("/error/message").asText()).contains("ABHA address differs");
        JsonNode unknown = linkInit(UUID.randomUUID(), address, uhid(patient), List.of(encounter.toString()));
        assertThat(unknown.at("/error/code").asInt()).isEqualTo(1404);
        ABDM.assertNoMore(SMS, sms);
    }

    @Test
    void wrongOtpsAndOtpFloodingAreLimited() throws Exception {
        String mobile = newMobile();
        UUID patient = patient("Lata", "Iyer", "F", 1975, mobile);
        UUID encounter = opdConsultation(patient, VISIT);
        String address = "lata." + mobile.substring(6) + "@sbx";
        UUID transaction = UUID.randomUUID();
        discover(transaction, address, "Lata Iyer", "F", 1975, mobile, null);

        int sms = ABDM.count(SMS);
        String linkRef = linkInit(transaction, address, uhid(patient), List.of(encounter.toString()))
                .at("/link/referenceNumber").asText();
        String otp = ABDM.await(SMS, sms + 1).body().at("/variables/otp").asText();
        String bad = otp.equals("000000") ? "111111" : "000000";
        assertThat(confirm(linkRef, bad).at("/error/message").asText()).isEqualTo("Wrong OTP");
        assertThat(confirm(linkRef, bad).at("/error/message").asText()).isEqualTo("Wrong OTP");
        assertThat(confirm(linkRef, bad).at("/error/message").asText()).contains("too many times");
        assertThat(confirm(linkRef, otp).at("/error/message").asText()).as("void after 3 wrong entries")
                .contains("closed");
        assertThat(jdbc.sql("SELECT link_status FROM abdm.care_context WHERE encounter_id = :e").param("e", encounter)
                .query(String.class).single()).isEqualTo("NOT_LINKED");

        for (int i = 2; i <= 5; i++) {
            assertThat(linkInit(transaction, address, uhid(patient), List.of(encounter.toString())).has("link")).isTrue();
        }
        JsonNode sixth = linkInit(transaction, address, uhid(patient), List.of(encounter.toString()));
        assertThat(sixth.at("/error/code").asInt()).as("max 5 OTPs per patient per hour").isEqualTo(1429);
        ABDM.assertNoMore(SMS, sms + 5);
    }

    @Test
    void anSmsOutageIsReportedAndLeavesNothingOpen() throws Exception {
        String mobile = newMobile();
        UUID patient = patient("Ravi", "Nair", "M", 1988, mobile);
        UUID encounter = opdConsultation(patient, VISIT);
        String address = "ravi." + mobile.substring(6) + "@sbx";
        UUID transaction = UUID.randomUUID();
        discover(transaction, address, "Ravi Nair", "M", 1988, mobile, null);
        ABDM.smsStatus(503);
        try {
            JsonNode init = linkInit(transaction, address, uhid(patient), List.of(encounter.toString()));
            assertThat(init.at("/error/message").asText()).isEqualTo("The OTP could not be sent; try again later");
        } finally {
            ABDM.smsStatus(202);
        }
        assertThat(jdbc.sql("SELECT status FROM abdm.link_session WHERE patient_id = :p").param("p", patient)
                .query(String.class).list()).containsExactly("FAILED");
    }

    // ------------------------------------------------------------------ ABDM side of the conversation

    private JsonNode discover(UUID transaction, String address, String name, String gender, int year, String mobile,
                              String uhid) throws Exception {
        List<Map<String, String>> verified = new ArrayList<>();
        if (mobile != null) {
            verified.add(Map.of("type", "MOBILE", "value", "+91-" + mobile));
        }
        List<Map<String, String>> unverified = uhid == null ? List.of() : List.of(Map.of("type", "MR", "value", uhid));
        int before = ABDM.count(ON_DISCOVER);
        callback("/api/v3/hip/patient/care-context/discover", UUID.randomUUID(), Map.of(
                "transactionId", transaction.toString(),
                "patient", Map.of("id", address, "name", name, "gender", gender, "yearOfBirth", year,
                        "verifiedIdentifiers", verified, "unverifiedIdentifiers", unverified)))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        return ABDM.await(ON_DISCOVER, before + 1).body();
    }

    private JsonNode linkInit(UUID transaction, String address, String uhid, List<String> references) throws Exception {
        int before = ABDM.count(ON_INIT);
        callback("/api/v3/hip/link/care-context/init", UUID.randomUUID(), Map.of(
                "transactionId", transaction.toString(),
                "abhaAddress", address,
                "patient", List.of(Map.of("referenceNumber", uhid, "hiType", "OPConsultation", "count", references.size(),
                        "careContexts", references.stream().map(r -> Map.of("referenceNumber", r)).toList()))))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        return ABDM.await(ON_INIT, before + 1).body();
    }

    private JsonNode confirm(String linkRef, String token) throws Exception {
        int before = ABDM.count(ON_CONFIRM);
        callback("/api/v3/hip/link/care-context/confirm", UUID.randomUUID(),
                Map.of("confirmation", Map.of("linkRefNumber", linkRef, "token", token)))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        return ABDM.await(ON_CONFIRM, before + 1).body();
    }

    private static List<String> references(JsonNode answer) {
        List<String> refs = new ArrayList<>();
        answer.path("patient").forEach(g -> g.path("careContexts").forEach(c -> {
            if (!refs.contains(c.path("referenceNumber").asText())) {
                refs.add(c.path("referenceNumber").asText());
            }
        }));
        return refs;
    }
}
