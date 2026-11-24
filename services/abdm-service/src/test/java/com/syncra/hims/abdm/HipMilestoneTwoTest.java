package com.syncra.hims.abdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.crypto.Fidelius;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2 (HIP): care-context linking, consent notification, and an HIU's health-information
 * request answered with encrypted FHIR records — against the simulated gateway and HIU.
 */
class HipMilestoneTwoTest extends AbdmTestSupport {

    static final Instant VISIT = Instant.parse("2026-11-10T05:00:00Z");

    @Test
    void careContextsAreLinkedWithALinkTokenThatIsThenReused() throws Exception {
        UUID patient = patient("Ramesh", "M", 1975);
        String abha = newAbhaNumber();
        String address = "rk." + abha.substring(8) + "@sbx";
        verifiedAbha(patient, abha, address);
        UUID first = opdConsultation(patient, VISIT);
        int tokenCalls = ABDM.count("/v3/token/generate-token");
        int linkCalls = ABDM.count("/hip/v3/link/carecontext");

        JsonNode pending = body(perform(post("/api/v1/abdm/encounters/" + first + "/care-context"), MRD)
                .andExpect(status().isAccepted()));
        assertThat(pending.path("linkStatus").asText()).isEqualTo("PENDING");
        assertThat(pending.path("hiTypes")).extracting(JsonNode::asText)
                .containsExactly("OPConsultation", "Prescription", "WellnessRecord");

        FakeAbdm.Received tokenRequest = ABDM.await("/v3/token/generate-token", tokenCalls + 1);
        assertThat(tokenRequest.header("X-HIP-ID")).isEqualTo(HIP_ID);
        assertThat(tokenRequest.body().path("abhaNumber").asText()).isEqualTo(hyphenated(abha));
        assertThat(tokenRequest.body().path("abhaAddress").asText()).isEqualTo(address);
        assertThat(tokenRequest.body().path("gender").asText()).isEqualTo("M");
        assertThat(tokenRequest.body().path("yearOfBirth").asInt()).isEqualTo(1975);

        callback("/api/v3/hip/token/on-generate-token", UUID.randomUUID(), Map.of(
                "abhaAddress", address, "linkToken", "link-token-" + abha,
                "response", Map.of("requestId", tokenRequest.header("REQUEST-ID"))))
                .andExpect(status().isAccepted());

        FakeAbdm.Received link = ABDM.await("/hip/v3/link/carecontext", linkCalls + 1);
        assertThat(link.header("X-LINK-TOKEN")).isEqualTo("link-token-" + abha);
        assertThat(link.body().path("patient")).as("one group per record type").extracting(g -> g.path("hiType").asText())
                .containsExactly("OPConsultation", "Prescription", "WellnessRecord");
        JsonNode group = link.body().path("patient").get(0);
        assertThat(group.path("referenceNumber").asText()).isEqualTo(uhid(patient));
        assertThat(group.path("hiType").asText()).isEqualTo("OPConsultation");
        assertThat(group.at("/careContexts/0/referenceNumber").asText()).isEqualTo(first.toString());
        assertThat(group.at("/careContexts/0/display").asText()).startsWith("OP consultation — General Medicine — 10 Nov 2026");

        callback("/api/v3/link/on_carecontext", UUID.randomUUID(), Map.of(
                "abhaAddress", address, "status", "Successfully Linked care context",
                "response", Map.of("requestId", link.header("REQUEST-ID"))))
                .andExpect(status().isAccepted());
        perform(get("/api/v1/abdm/patients/" + patient + "/care-contexts"), MRD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].linkStatus").value("LINKED"))
                .andExpect(jsonPath("$[0].linkedAbhaAddress").value(address));

        // A second visit reuses the cached link token: straight to the link call
        UUID second = opdConsultation(patient, VISIT.plusSeconds(86_400));
        perform(post("/api/v1/abdm/encounters/" + second + "/care-context"), MRD).andExpect(status().isAccepted());
        FakeAbdm.Received secondLink = ABDM.await("/hip/v3/link/carecontext", linkCalls + 2);
        assertThat(secondLink.header("X-LINK-TOKEN")).isEqualTo("link-token-" + abha);
        assertThat(secondLink.body().at("/patient/0/careContexts")).hasSize(1);
        ABDM.assertNoMore("/v3/token/generate-token", tokenCalls + 1);
    }

    @Test
    void linkingNeedsAVerifiedAbhaAndSomethingToShare() throws Exception {
        UUID patient = patient("Ramesh", "M", 1975);
        UUID encounter = opdConsultation(patient, VISIT);
        perform(post("/api/v1/abdm/encounters/" + encounter + "/care-context"), MRD)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ABHA_NOT_VERIFIED"));

        UUID empty = jdbc.sql("""
                INSERT INTO clinical.encounter (patient_id, encounter_type, department_id)
                SELECT :p, 'OPD', id FROM core.department WHERE code = 'GMED' RETURNING id
                """).param("p", patient).query(UUID.class).single();
        perform(post("/api/v1/abdm/encounters/" + empty + "/care-context"), MRD)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOTHING_TO_LINK"));
    }

    @Test
    void unsolicitedLinkTokensAreIgnored() throws Exception {
        int linkCalls = ABDM.count("/hip/v3/link/carecontext");
        callback("/api/v3/hip/token/on-generate-token", UUID.randomUUID(), Map.of(
                "abhaAddress", "someone@sbx", "linkToken", "planted-token",
                "response", Map.of("requestId", UUID.randomUUID().toString())))
                .andExpect(status().isAccepted());
        ABDM.assertNoMore("/hip/v3/link/carecontext", linkCalls);
    }

    @Test
    void consentedRecordsAreEncryptedPushedToTheHiuAndReported() throws Exception {
        Fixture f = linkedPatient();
        UUID consentId = UUID.randomUUID();
        UUID notifyRequest = UUID.randomUUID();
        java.util.function.Predicate<FakeAbdm.Received> thisNotify =
                r -> r.body().at("/response/requestId").asText().equals(notifyRequest.toString());
        callback("/api/v3/consent/request/hip/notify", notifyRequest, grant(consentId, f, "2026-01-01T00:00:00.000Z",
                "2026-12-31T23:59:59.000Z")).andExpect(status().isAccepted());
        FakeAbdm.Received onNotify = ABDM.awaitMatching("/consent/v3/request/hip/on-notify", thisNotify);
        assertThat(onNotify.body().at("/acknowledgement/status").asText()).isEqualTo("OK");
        assertThat(onNotify.body().at("/acknowledgement/consentId").asText()).isEqualTo(consentId.toString());
        assertThat(onNotify.body().at("/response/requestId").asText()).isEqualTo(notifyRequest.toString());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM abdm.consent_care_context cc JOIN abdm.consent_artefact c ON c.id = cc.consent_id
                WHERE c.consent_artefact_id = :id
                """).param("id", consentId).query(Integer.class).single()).isEqualTo(1);

        // ABDM retries the notification: already answered, not processed again
        callback("/api/v3/consent/request/hip/notify", notifyRequest, grant(consentId, f, "2026-01-01T00:00:00.000Z",
                "2026-12-31T23:59:59.000Z")).andExpect(status().isAccepted());
        assertThat(ABDM.countMatching("/consent/v3/request/hip/on-notify", thisNotify)).isEqualTo(1);

        Fidelius.KeyMaterial hiu = Fidelius.generateKeyMaterial();
        UUID transaction = UUID.randomUUID();
        int acks = ABDM.count("/health-information/hip/on-request");
        int pushes = ABDM.count("/hiu/data/push");
        int reports = ABDM.count("/data-flow/v3/health-information/notify");
        UUID hiRequest = UUID.randomUUID();
        callback("/api/v3/hip/health-information/request", hiRequest,
                healthInformationRequest(transaction, consentId, "2026-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu))
                .andExpect(status().isAccepted());

        FakeAbdm.Received ack = ABDM.await("/health-information/hip/on-request", acks + 1);
        assertThat(ack.body().at("/hiRequest/sessionStatus").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.body().at("/hiRequest/transactionId").asText()).isEqualTo(transaction.toString());
        assertThat(ack.body().at("/response/requestId").asText()).isEqualTo(hiRequest.toString());

        FakeAbdm.Received push = ABDM.await("/hiu/data/push", pushes + 1);
        assertThat(push.header("Authorization")).as("session token must not leak to the HIU").isNull();
        JsonNode page = push.body();
        assertThat(page.path("transactionId").asText()).isEqualTo(transaction.toString());
        assertThat(page.path("pageNumber").asInt()).isEqualTo(1);
        assertThat(page.path("pageCount").asInt()).isEqualTo(1);
        assertThat(page.at("/keyMaterial/curve").asText()).isEqualTo("Curve25519");
        assertThat(page.at("/keyMaterial/dhPublicKey/parameters").asText()).isEqualTo("Curve25519/32byte random key");

        List<String> types = new ArrayList<>();
        for (JsonNode entry : page.path("entries")) {
            assertThat(entry.path("media").asText()).isEqualTo("application/fhir+json");
            assertThat(entry.path("careContextReference").asText()).isEqualTo(f.encounter().toString());
            // The HIU decrypts with its private key and the HIP's one-time public key
            String plain = Fidelius.decrypt(entry.path("content").asText(), hiu.nonce(),
                    page.at("/keyMaterial/nonce").asText(), hiu.privateKey(),
                    page.at("/keyMaterial/dhPublicKey/keyValue").asText());
            assertThat(entry.path("checksum").asText()).isEqualTo(digest("MD5", plain));
            Bundle bundle = new com.syncra.hims.abdm.fhir.FhirJson().decode(plain);
            assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
            types.add(((Composition) bundle.getEntryFirstRep().getResource()).getType().getCodingFirstRep().getCode());
        }
        assertThat(types).containsExactlyInAnyOrder("371530004", "440545006");

        FakeAbdm.Received report = ABDM.await("/data-flow/v3/health-information/notify", reports + 1);
        assertThat(report.body().at("/notification/statusNotification/sessionStatus").asText()).isEqualTo("TRANSFERRED");
        assertThat(report.body().at("/notification/statusNotification/statusResponses/0/hiStatus").asText())
                .isEqualTo("DELIVERED");
        assertThat(report.body().at("/notification/consentId").asText()).isEqualTo(consentId.toString());
        assertThat(report.body().at("/notification/notifier/id").asText()).isEqualTo(HIP_ID);

        assertThat(jdbc.sql("SELECT status FROM abdm.health_information_request WHERE transaction_id = :t")
                .param("t", transaction).query(String.class).single()).isEqualTo("TRANSFERRED");
        assertThat(jdbc.sql("""
                SELECT t.status FROM abdm.health_information_transfer t
                JOIN abdm.health_information_request r ON r.id = t.hi_request_id WHERE r.transaction_id = :t
                """).param("t", transaction).query(String.class).list()).containsExactly("DELIVERED", "DELIVERED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.event_log WHERE action = 'SHARE' AND entity_id = :t AND patient_id = :p
                """).param("t", transaction.toString()).param("p", f.patient()).query(Integer.class).single()).isEqualTo(1);

        JsonNode consents = body(perform(get("/api/v1/abdm/patients/" + f.patient() + "/consents"), MRD)
                .andExpect(status().isOk()));
        assertThat(consents.at("/0/transfers/0/status").asText()).isEqualTo("TRANSFERRED");
        assertThat(consents.at("/0/transfers/0/records")).hasSize(2);
    }

    @Test
    void requestsOutsideTheConsentOrAfterRevocationAreRefused() throws Exception {
        Fixture f = linkedPatient();
        UUID consentId = UUID.randomUUID();
        int notifies = ABDM.count("/consent/v3/request/hip/on-notify");
        callback("/api/v3/consent/request/hip/notify", UUID.randomUUID(),
                grant(consentId, f, "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.000Z"))
                .andExpect(status().isAccepted());
        ABDM.await("/consent/v3/request/hip/on-notify", notifies + 1);
        Fidelius.KeyMaterial hiu = Fidelius.generateKeyMaterial();
        int acks = ABDM.count("/health-information/hip/on-request");
        int pushes = ABDM.count("/hiu/data/push");

        callback("/api/v3/hip/health-information/request", UUID.randomUUID(), healthInformationRequest(UUID.randomUUID(),
                consentId, "2025-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu)).andExpect(status().isAccepted());
        FakeAbdm.Received outside = ABDM.await("/health-information/hip/on-request", acks + 1);
        assertThat(outside.body().at("/hiRequest/sessionStatus").asText()).isEqualTo("ERRORED");
        assertThat(outside.body().at("/error/message").asText()).contains("outside the consented period");

        callback("/api/v3/consent/request/hip/notify", UUID.randomUUID(), Map.of("notification", Map.of(
                "status", "REVOKED", "consentId", consentId.toString(), "consentDetail", Map.of())))
                .andExpect(status().isAccepted());
        ABDM.await("/consent/v3/request/hip/on-notify", notifies + 2);
        callback("/api/v3/hip/health-information/request", UUID.randomUUID(), healthInformationRequest(UUID.randomUUID(),
                consentId, "2026-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu)).andExpect(status().isAccepted());
        FakeAbdm.Received revoked = ABDM.await("/health-information/hip/on-request", acks + 2);
        assertThat(revoked.body().at("/hiRequest/sessionStatus").asText()).isEqualTo("ERRORED");
        assertThat(revoked.body().at("/error/message").asText()).contains("revoked");

        callback("/api/v3/hip/health-information/request", UUID.randomUUID(), healthInformationRequest(UUID.randomUUID(),
                UUID.randomUUID(), "2026-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu))
                .andExpect(status().isAccepted());
        assertThat(ABDM.await("/health-information/hip/on-request", acks + 3).body().at("/error/message").asText())
                .isEqualTo("Unknown consent artefact");
        ABDM.assertNoMore("/hiu/data/push", pushes);
    }

    @Test
    void diagnosticReportsAreLinkedAndSharedOnceResultsArrive() throws Exception {
        Fixture f = linkedPatient();
        perform(get("/api/v1/abdm/encounters/" + f.encounter() + "/records"), MRD)
                .andExpect(jsonPath("$.available.length()").value(3));

        reportedInvestigations(f.patient(), f.encounter(), VISIT.plusSeconds(4 * 3600));
        JsonNode records = body(perform(get("/api/v1/abdm/encounters/" + f.encounter() + "/records"), MRD)
                .andExpect(status().isOk()));
        assertThat(records.path("available")).extracting(JsonNode::asText).contains("DiagnosticReport");

        // The visit was linked before the results: linking again adds the new record type
        int tokenCalls = ABDM.count("/v3/token/generate-token");
        JsonNode widened = body(perform(post("/api/v1/abdm/encounters/" + f.encounter() + "/care-context"), MRD)
                .andExpect(status().isAccepted()));
        assertThat(widened.path("linkStatus").asText()).isEqualTo("PENDING");
        assertThat(widened.path("hiTypes")).extracting(JsonNode::asText)
                .containsExactly("DiagnosticReport", "OPConsultation", "Prescription", "WellnessRecord");
        ABDM.await("/v3/token/generate-token", tokenCalls + 1);

        UUID consentId = UUID.randomUUID();
        int notifies = ABDM.count("/consent/v3/request/hip/on-notify");
        Map<String, Object> grant = grant(consentId, f, "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.000Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = new java.util.HashMap<>((Map<String, Object>)
                ((Map<String, Object>) grant.get("notification")).get("consentDetail"));
        detail.put("hiTypes", List.of("DiagnosticReport"));
        Map<String, Object> notification = new java.util.HashMap<>((Map<String, Object>) grant.get("notification"));
        notification.put("consentDetail", detail);
        callback("/api/v3/consent/request/hip/notify", UUID.randomUUID(), Map.of("notification", notification))
                .andExpect(status().isAccepted());
        ABDM.await("/consent/v3/request/hip/on-notify", notifies + 1);

        Fidelius.KeyMaterial hiu = Fidelius.generateKeyMaterial();
        int pushes = ABDM.count("/hiu/data/push");
        callback("/api/v3/hip/health-information/request", UUID.randomUUID(), healthInformationRequest(UUID.randomUUID(),
                consentId, "2026-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu)).andExpect(status().isAccepted());
        JsonNode page = ABDM.await("/hiu/data/push", pushes + 1).body();
        assertThat(page.path("entries")).as("only the consented type").hasSize(1);
        Bundle bundle = new com.syncra.hims.abdm.fhir.FhirJson().decode(Fidelius.decrypt(
                page.at("/entries/0/content").asText(), hiu.nonce(), page.at("/keyMaterial/nonce").asText(),
                hiu.privateKey(), page.at("/keyMaterial/dhPublicKey/keyValue").asText()));
        assertThat(((Composition) bundle.getEntryFirstRep().getResource()).getType().getCodingFirstRep().getCode())
                .isEqualTo("721981007");
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType())
                .filteredOn(type -> type.equals("DiagnosticReport")).hasSize(2);
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType()).contains("ImagingStudy");
    }

    @Test
    void immunizationRecordsAreSharedUnderConsent() throws Exception {
        Fixture f = linkedPatient();
        vaccination(f.patient(), f.encounter(), VISIT.plusSeconds(1800));
        jdbc.sql("""
                INSERT INTO clinical.immunization (encounter_id, patient_id, vaccine_code, dose_number, administered_at,
                                                   lot_number, expiry_date, recorded_by, is_entered_in_error,
                                                   entered_in_error_reason)
                VALUES (:e, :p, 'PCV', 1, :at, 'WRONG-1', DATE '2027-01-31', 'test.nurse', true, 'Wrong patient')
                """).param("e", f.encounter()).param("p", f.patient()).param("at", utc(VISIT.plusSeconds(1900))).update();
        JsonNode records = body(perform(get("/api/v1/abdm/encounters/" + f.encounter() + "/records"), MRD)
                .andExpect(status().isOk()));
        assertThat(records.path("available")).extracting(JsonNode::asText).contains("ImmunizationRecord");
        perform(get("/api/v1/abdm/encounters/" + f.encounter() + "/records/ImmunizationRecord"), DOCTOR)
                .andExpect(status().isOk());
        jdbc.sql("UPDATE abdm.care_context SET hi_types = hi_types || ARRAY['ImmunizationRecord']::varchar[] WHERE encounter_id = :e")
                .param("e", f.encounter()).update();

        UUID consentId = UUID.randomUUID();
        int notifies = ABDM.count("/consent/v3/request/hip/on-notify");
        Map<String, Object> grant = grant(consentId, f, "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.000Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> notification = new java.util.HashMap<>((Map<String, Object>) grant.get("notification"));
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = new java.util.HashMap<>((Map<String, Object>) notification.get("consentDetail"));
        detail.put("hiTypes", List.of("ImmunizationRecord"));
        notification.put("consentDetail", detail);
        callback("/api/v3/consent/request/hip/notify", UUID.randomUUID(), Map.of("notification", notification))
                .andExpect(status().isAccepted());
        ABDM.await("/consent/v3/request/hip/on-notify", notifies + 1);

        Fidelius.KeyMaterial hiu = Fidelius.generateKeyMaterial();
        int pushes = ABDM.count("/hiu/data/push");
        callback("/api/v3/hip/health-information/request", UUID.randomUUID(), healthInformationRequest(UUID.randomUUID(),
                consentId, "2026-06-01T00:00:00.000Z", "2026-11-30T00:00:00.000Z", hiu)).andExpect(status().isAccepted());
        JsonNode page = ABDM.await("/hiu/data/push", pushes + 1).body();
        assertThat(page.path("entries")).hasSize(1);
        Bundle bundle = new com.syncra.hims.abdm.fhir.FhirJson().decode(Fidelius.decrypt(
                page.at("/entries/0/content").asText(), hiu.nonce(), page.at("/keyMaterial/nonce").asText(),
                hiu.privateKey(), page.at("/keyMaterial/dhPublicKey/keyValue").asText()));
        assertThat(((Composition) bundle.getEntryFirstRep().getResource()).getType().getCodingFirstRep().getCode())
                .isEqualTo("41000179103");
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType())
                .contains("Immunization", "ImmunizationRecommendation");
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType())
                .as("the entry marked in error is not shared").filteredOn("Immunization"::equals).hasSize(1);
    }

    @Test
    void staffCanDownloadAnEncountersFhirRecord() throws Exception {
        UUID patient = patient("Ramesh", "M", 1975);
        UUID encounter = opdConsultation(patient, VISIT);

        perform(get("/api/v1/abdm/encounters/" + encounter + "/records"), MRD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available.length()").value(3))
                .andExpect(jsonPath("$.available[0]").value(org.hamcrest.Matchers.oneOf("OPConsultation", "Prescription")));
        String json = perform(get("/api/v1/abdm/encounters/" + encounter + "/records/OPConsultation"), DOCTOR)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Bundle bundle = new com.syncra.hims.abdm.fhir.FhirJson().decode(json);
        assertThat(bundle.getEntry()).extracting(e -> e.getResource().fhirType())
                .contains("Composition", "Patient", "Practitioner", "Encounter", "Observation", "MedicationRequest");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event_log WHERE action = 'EXPORT' AND patient_id = :p")
                .param("p", patient).query(Integer.class).single()).isEqualTo(1);

        perform(get("/api/v1/abdm/encounters/" + encounter + "/records/OPConsultation"), RECEPTION)
                .andExpect(status().isForbidden());
        perform(get("/api/v1/abdm/encounters/" + encounter + "/records/DischargeSummary"), DOCTOR)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_SUCH_RECORD"));
    }

    @Test
    void labValuesAndPharmacyBillsAreReadIntoTheirRecords() throws Exception {
        UUID patient = patient("Ramesh", "M", 1975);
        UUID encounter = opdConsultation(patient, VISIT);
        jdbc.sql("""
                UPDATE clinical.service_order SET status = 'REPORTED', sample_collected_at = :sample, completed_at = :done,
                       result_summary = 'Severe thrombocytopenia',
                       result_values = CAST(:values AS jsonb)
                WHERE encounter_id = :e AND order_type = 'LABORATORY'
                """).param("sample", utc(VISIT.plusSeconds(900))).param("done", utc(VISIT.plusSeconds(4000)))
                .param("values", """
                        [{"code":"HB","name":"Haemoglobin","value":13.2,"text":null,"unit":"g/dL","referenceRange":"13-17","flag":"N"},
                         {"code":"PLT","name":"Platelet count","value":18,"text":null,"unit":"10^3/uL","referenceRange":"150-410","flag":"C"},
                         {"code":"SMEAR","name":"Peripheral smear","value":null,"text":"Giant platelets seen","unit":null,"referenceRange":null,"flag":null}]
                        """)
                .param("e", encounter).update();

        // Taxim-O sold at the counter from a batch, then billed (DRAFT -> FINAL issues the number).
        String batchNumber = "TX" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000, 999_999);
        UUID batch = jdbc.sql("""
                INSERT INTO pharmacy.batch (drug_id, batch_number, expiry_date, mrp, purchase_rate)
                SELECT id, :bn, DATE '2027-01-31', 10.90, 7.80 FROM pharmacy.drug WHERE code = 'CFX-200' RETURNING id
                """).param("bn", batchNumber).query(UUID.class).single();
        UUID dispense = jdbc.sql("""
                INSERT INTO pharmacy.dispense (prescription_id, patient_id, store_id, dispensed_by)
                SELECT p.id, p.patient_id, s.id, :staff FROM clinical.prescription p, pharmacy.store s
                WHERE p.encounter_id = :e AND s.code = 'DISPENSARY' RETURNING id
                """).param("staff", doctor()).param("e", encounter).query(UUID.class).single();
        UUID line = jdbc.sql("""
                INSERT INTO pharmacy.dispense_line (dispense_id, drug_id, batch_id, quantity, mrp, gst_rate, schedule)
                SELECT :d, drug_id, id, 6, 10.90, 12, 'H1' FROM pharmacy.batch WHERE id = :b RETURNING id
                """).param("d", dispense).param("b", batch).query(UUID.class).single();
        UUID bill = jdbc.sql("""
                INSERT INTO billing.bill (bill_type, patient_id, encounter_id, tariff_plan_id)
                SELECT 'PHARMACY', :p, :e, id FROM billing.tariff_plan WHERE code = 'GENERAL' RETURNING id
                """).param("p", patient).param("e", encounter).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO billing.bill_item (bill_id, service_id, description, quantity, unit_price, gst_rate,
                                               source_type, source_id)
                SELECT :bill, id, 'Taxim-O 200 200 mg', 6, 9.73, 12, 'DISPENSE_LINE', :line
                FROM billing.service WHERE code = 'PHM-DRUG'
                """).param("bill", bill).param("line", line).update();
        jdbc.sql("UPDATE billing.bill SET status = 'FINAL', round_off = -0.39, finalized_by = 'test' WHERE id = :id")
                .param("id", bill).update();

        JsonNode records = body(perform(get("/api/v1/abdm/encounters/" + encounter + "/records"), MRD)
                .andExpect(status().isOk()));
        assertThat(records.path("available")).extracting(JsonNode::asText)
                .contains("DiagnosticReport", "WellnessRecord", "Invoice").doesNotContain("HealthDocumentRecord");

        Bundle report = record(encounter, "DiagnosticReport");
        List<org.hl7.fhir.r4.model.Observation> values = report.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                .filter(org.hl7.fhir.r4.model.Observation.class::isInstance).map(org.hl7.fhir.r4.model.Observation.class::cast).toList();
        assertThat(values).extracting(o -> o.getCode().getText())
                .containsExactly("Haemoglobin", "Platelet count", "Peripheral smear");
        org.hl7.fhir.r4.model.Observation platelets = values.get(1);
        assertThat(platelets.getCode().getCodingFirstRep().getCode()).as("LOINC from the test template").isEqualTo("777-3");
        assertThat(platelets.getValueQuantity().getCode()).isEqualTo("10*3/uL");
        assertThat(platelets.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("LL");
        assertThat(values.get(2).getValueStringType().getValue()).isEqualTo("Giant platelets seen");
        org.hl7.fhir.r4.model.DiagnosticReport cbc = report.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                .filter(org.hl7.fhir.r4.model.DiagnosticReport.class::isInstance)
                .map(org.hl7.fhir.r4.model.DiagnosticReport.class::cast).findFirst().orElseThrow();
        assertThat(cbc.getCode().getCodingFirstRep().getCode()).isEqualTo("58410-2");
        assertThat(cbc.getResult()).hasSize(3);

        Bundle invoices = record(encounter, "Invoice");
        org.hl7.fhir.r4.model.Invoice invoice = invoices.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                .filter(org.hl7.fhir.r4.model.Invoice.class::isInstance).map(org.hl7.fhir.r4.model.Invoice.class::cast)
                .findFirst().orElseThrow();
        assertThat(invoice.getIdentifierFirstRep().getValue()).startsWith("PHB/");
        assertThat(invoice.getTotalGross().getValue()).isEqualByComparingTo("65.00");
        assertThat(invoice.getLineItemFirstRep().getPriceComponent())
                .extracting(pc -> pc.getCode().getCodingFirstRep().getDisplay() + " " + pc.getAmount().getValue().toPlainString())
                .containsExactly("Rate 58.38", "MRP 65.40", "CGST 3.51", "SGST 3.50");
        org.hl7.fhir.r4.model.Medication medication = invoices.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
                .filter(org.hl7.fhir.r4.model.Medication.class::isInstance).map(org.hl7.fhir.r4.model.Medication.class::cast)
                .findFirst().orElseThrow();
        assertThat(medication.getCode().getText()).isEqualTo("Taxim-O 200 200 mg");
        assertThat(medication.getBatch().getLotNumber()).isEqualTo(batchNumber);
    }

    private Bundle record(UUID encounter, String type) throws Exception {
        String json = perform(get("/api/v1/abdm/encounters/" + encounter + "/records/" + type), DOCTOR)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new com.syncra.hims.abdm.fhir.FhirJson().decode(json);
    }

    // ------------------------------------------------------------------ fixtures

    record Fixture(UUID patient, UUID encounter, String abhaAddress, String uhid) {
    }

    private Fixture linkedPatient() {
        UUID patient = patient("Ramesh", "M", 1975);
        String abha = newAbhaNumber();
        String address = "rk." + abha.substring(8) + "@sbx";
        verifiedAbha(patient, abha, address);
        UUID encounter = opdConsultation(patient, VISIT);
        linkedCareContext(patient, encounter, address);
        return new Fixture(patient, encounter, address, uhid(patient));
    }

    private static Map<String, Object> grant(UUID consentId, Fixture f, String from, String to) {
        return Map.of("notification", Map.of(
                "status", "GRANTED",
                "consentId", consentId.toString(),
                "signature", "c2lnbmF0dXJl",
                "grantAcknowledgement", false,
                "consentDetail", Map.of(
                        "consentId", consentId.toString(),
                        "createdAt", "2026-11-19T10:00:00.000Z",
                        "patient", Map.of("id", f.abhaAddress()),
                        "careContexts", List.of(Map.of("patientReference", f.uhid(),
                                "careContextReference", f.encounter().toString())),
                        "purpose", Map.of("text", "Care Management", "code", "CAREMGT"),
                        "hip", Map.of("id", HIP_ID, "name", "Syncra Test Hospital"),
                        "hiu", Map.of("id", "HIU-TEST-1", "name", "Test HIU"),
                        "consentManager", Map.of("id", "sbx"),
                        "hiTypes", List.of("OPConsultation", "Prescription"),
                        "permission", Map.of(
                                "accessMode", "VIEW",
                                "dateRange", Map.of("from", from, "to", to),
                                "dataEraseAt", "2027-01-31T00:00:00.000Z",
                                "frequency", Map.of("unit", "HOUR", "value", 1, "repeats", 0)))));
    }

    private static Map<String, Object> healthInformationRequest(UUID transaction, UUID consentId, String from, String to,
                                                                Fidelius.KeyMaterial hiu) {
        return Map.of(
                "transactionId", transaction.toString(),
                "hiRequest", Map.of(
                        "consent", Map.of("id", consentId.toString()),
                        "dateRange", Map.of("from", from, "to", to),
                        "dataPushUrl", ABDM.hiuPushUrl(),
                        "keyMaterial", Map.of(
                                "cryptoAlg", "ECDH",
                                "curve", "Curve25519",
                                "dhPublicKey", Map.of("expiry", Instant.now().plusSeconds(3600).toString(),
                                        "parameters", "Curve25519/32byte random key", "keyValue", hiu.x509PublicKey()),
                                "nonce", hiu.nonce())));
    }

    private static String hyphenated(String abha) {
        return abha.substring(0, 2) + "-" + abha.substring(2, 6) + "-" + abha.substring(6, 10) + "-" + abha.substring(10);
    }

    private static String digest(String algorithm, String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
