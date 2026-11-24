package com.syncra.hims.abdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.crypto.Fidelius;
import com.syncra.hims.abdm.fhir.FhirJson;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Milestone 3: this hospital as HIU. A doctor asks the patient's consent; ABDM (simulated)
 * answers and the patient approves; the consent is fetched; records are requested with a
 * one-time key and push URL; another hospital (simulated) pushes encrypted FHIR documents; they
 * are checked, stored, reported back, shown to the doctor, and erased when consent is revoked.
 */
class HiuMilestoneThreeTest extends AbdmTestSupport {

    static final RequestPostProcessor TREATING = as("hiu.doctor", "DOCTOR");
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void aDoctorSeesRecordsAnotherHospitalSharedUnderConsentUntilItIsRevoked() throws Exception {
        String address = patientWithAbha();
        String uhid = uhidOf(address);
        LocalDate today = LocalDate.now(IST);

        // 1. The doctor asks; the request goes to ABDM as this HIU, with the doctor's registration.
        JsonNode created = body(postJson("/api/v1/abdm/hiu/consent-requests", Map.of("uhid", uhid, "purpose", "CAREMGT",
                "hiTypes", List.of("OPConsultation", "DiagnosticReport"), "from", today.minusYears(2).toString(),
                "to", today.toString(), "keepUntil", today.plusDays(7).toString()), TREATING)
                .andExpect(status().isAccepted()));
        assertThat(created.path("status").asText()).isEqualTo("REQUESTED");
        FakeAbdm.Received init = ABDM.awaitMatching("/consent/v3/request/init",
                r -> r.body().at("/consent/patient/id").asText().equals(address));
        assertThat(init.header("X-HIU-ID")).isEqualTo(HIP_ID);
        assertThat(init.body().at("/consent/hiu/id").asText()).isEqualTo(HIP_ID);
        assertThat(init.body().at("/consent/requester/name").asText()).isEqualTo("Dr Anil Mehta");
        assertThat(init.body().at("/consent/requester/identifier/value").asText()).isEqualTo("MMC/2011/12345");
        assertThat(init.body().at("/consent/purpose/code").asText()).isEqualTo("CAREMGT");
        assertThat(init.body().at("/consent/hiTypes")).extracting(JsonNode::asText)
                .containsExactly("OPConsultation", "DiagnosticReport");
        assertThat(init.body().at("/consent/permission/accessMode").asText()).isEqualTo("VIEW");

        UUID consentRequestId = UUID.randomUUID();
        callback("/api/v3/hiu/consent/request/on-init", UUID.randomUUID(), Map.of(
                "consentRequest", Map.of("id", consentRequestId.toString()),
                "response", Map.of("requestId", init.header("REQUEST-ID")))).andExpect(status().isAccepted());

        // 2. The patient approves in their app: one artefact (one HIP). It is acknowledged and fetched.
        UUID artefact = UUID.randomUUID();
        UUID notifyId = UUID.randomUUID();
        callback("/api/v3/hiu/consent/request/notify", notifyId, Map.of("notification", Map.of(
                "consentRequestId", consentRequestId.toString(), "status", "GRANTED",
                "consentArtefacts", List.of(Map.of("id", artefact.toString()))))).andExpect(status().isAccepted());
        FakeAbdm.Received ack = ABDM.awaitMatching("/consent/v3/request/hiu/on-notify",
                r -> r.body().at("/response/requestId").asText().equals(notifyId.toString()));
        assertThat(ack.body().at("/acknowledgement/0/consentId").asText()).isEqualTo(artefact.toString());
        assertThat(ack.body().at("/acknowledgement/0/status").asText()).isEqualTo("OK");
        FakeAbdm.Received fetch = ABDM.awaitMatching("/consent/v3/fetch",
                r -> r.body().path("consentId").asText().equals(artefact.toString()));

        // 3. The details arrive; records are asked for with a one-time key and push URL.
        callback("/api/v3/hiu/consent/on-fetch", UUID.randomUUID(), Map.of(
                "consent", Map.of("status", "GRANTED", "consentDetail", consentDetail(artefact, address, today)),
                "response", Map.of("requestId", fetch.header("REQUEST-ID")))).andExpect(status().isAccepted());
        FakeAbdm.Received hiRequest = ABDM.awaitMatching("/data-flow/v3/health-information/request",
                r -> r.body().at("/hiRequest/consent/id").asText().equals(artefact.toString()));
        String pushUrl = hiRequest.body().at("/hiRequest/dataPushUrl").asText();
        assertThat(pushUrl).matches("https://bridge\\.hims\\.test/hiu/v3/data/push/[A-Za-z0-9_-]{43}");
        assertThat(hiRequest.body().at("/hiRequest/keyMaterial/curve").asText()).isEqualTo("Curve25519");
        String hiuKey = hiRequest.body().at("/hiRequest/keyMaterial/dhPublicKey/keyValue").asText();
        String hiuNonce = hiRequest.body().at("/hiRequest/keyMaterial/nonce").asText();
        assertThat(jdbc.sql("SELECT count(*) FROM abdm.hiu_data_request WHERE consent_id = :c AND push_token_sha256 IS NOT NULL")
                .param("c", artefact).query(Integer.class).single()).isEqualTo(1);

        UUID transaction = UUID.randomUUID();
        callback("/api/v3/hiu/health-information/on-request", UUID.randomUUID(), Map.of(
                "hiRequest", Map.of("transactionId", transaction.toString(), "sessionStatus", "REQUESTED"),
                "response", Map.of("requestId", hiRequest.header("REQUEST-ID")))).andExpect(status().isAccepted());

        // 4. The other hospital pushes: one good document and one whose checksum does not match.
        String document = new FhirJson().encode(consultation());
        Fidelius.KeyMaterial hip = Fidelius.generateKeyMaterial();
        String encrypted = Fidelius.encrypt(document, hip.nonce(), hiuNonce, hip.privateKey(), hiuKey);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageNumber", 1);
        page.put("pageCount", 1);
        page.put("transactionId", transaction.toString());
        page.put("entries", List.of(
                Map.of("content", encrypted, "media", "application/fhir+json", "checksum", md5(document),
                        "careContextReference", "CC-OPD-1"),
                Map.of("content", encrypted, "media", "application/fhir+json", "checksum", "0".repeat(32),
                        "careContextReference", "CC-OPD-2")));
        page.put("keyMaterial", Map.of("cryptoAlg", "ECDH", "curve", "Curve25519", "dhPublicKey", Map.of(
                "expiry", Instant.now().plusSeconds(3600).toString(), "parameters", "Curve25519/32byte random key",
                "keyValue", hip.x509PublicKey()), "nonce", hip.nonce()));
        String path = URI.create(pushUrl).getPath();
        // A wrong secret or transaction is refused; nothing is needed but the URL (no token).
        mvc.perform(post("/hiu/v3/data/push/" + "A".repeat(43)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(page))).andExpect(status().isNotFound());
        Map<String, Object> wrongTransaction = new LinkedHashMap<>(page);
        wrongTransaction.put("transactionId", UUID.randomUUID().toString());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(wrongTransaction)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_TRANSACTION"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(page)))
                .andExpect(status().isAccepted());
        // The transfer is closed once its pages are in.
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(page)))
                .andExpect(status().isConflict());

        FakeAbdm.Received notified = ABDM.awaitMatching("/data-flow/v3/health-information/notify",
                r -> r.body().at("/notification/transactionId").asText().equals(transaction.toString()));
        assertThat(notified.body().at("/notification/notifier/type").asText()).isEqualTo("HIU");
        assertThat(notified.body().at("/notification/statusNotification/sessionStatus").asText()).isEqualTo("TRANSFERRED");
        assertThat(notified.body().at("/notification/statusNotification/hipId").asText()).isEqualTo("IN2910000099");
        Map<String, String> statuses = new LinkedHashMap<>();
        notified.body().at("/notification/statusNotification/statusResponses")
                .forEach(s -> statuses.put(s.path("careContextReference").asText(), s.path("hiStatus").asText()));
        assertThat(statuses).containsEntry("CC-OPD-1", "OK").containsEntry("CC-OPD-2", "ERRORED");

        // 5. The doctor sees the request, the hospital that answered, and the record.
        JsonNode consents = body(perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/consent-requests"), TREATING)
                .andExpect(status().isOk()));
        assertThat(consents.at("/0/status").asText()).isEqualTo("GRANTED");
        assertThat(consents.at("/0/artefacts/0/hipName").asText()).isEqualTo("City Care Hospital");
        assertThat(consents.at("/0/artefacts/0/transfers/0/status").asText()).isEqualTo("PARTIAL");
        assertThat(consents.at("/0/artefacts/0/transfers/0/records").asInt()).isEqualTo(1);
        JsonNode records = body(perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/records"), TREATING)
                .andExpect(status().isOk()));
        assertThat(records).hasSize(1);
        assertThat(records.at("/0/title").asText()).isEqualTo("OP Consultation");
        assertThat(records.at("/0/recordType").asText()).isEqualTo("OPConsultRecord");
        String recordId = records.at("/0/id").asText();
        JsonNode detail = body(perform(get("/api/v1/abdm/hiu/records/" + recordId), TREATING).andExpect(status().isOk()));
        assertThat(detail.at("/summary/author").asText()).isEqualTo("Dr Kavita Rao");
        assertThat(detail.at("/summary/sections/0/title").asText()).isEqualTo("Medications");
        assertThat(detail.at("/summary/sections/0/lines/0").asText())
                .isEqualTo("Tab Metformin 500 mg · 1 tab twice a day after food");
        assertThat(detail.at("/summary/sections/1/lines/0").asText()).startsWith("HbA1c: 8.1 % (H) · ref 4–5.6");
        perform(get("/api/v1/abdm/hiu/records/" + recordId + "/fhir"), TREATING).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.event_log WHERE entity_type = 'hiu_health_record' AND entity_id = :id")
                .param("id", recordId).query(Integer.class).single()).as("read and download audited").isEqualTo(2);
        perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/records"), RECEPTION).andExpect(status().isForbidden());
        perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/records"), MRD).andExpect(status().isForbidden());

        // 6. The patient revokes: what was received is erased.
        callback("/api/v3/hiu/consent/request/notify", UUID.randomUUID(), Map.of("notification", Map.of(
                "consentRequestId", consentRequestId.toString(), "status", "REVOKED",
                "consentArtefacts", List.of(Map.of("id", artefact.toString()))))).andExpect(status().isAccepted());
        assertThat(body(perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/records"), TREATING))).isEmpty();
        perform(get("/api/v1/abdm/hiu/records/" + recordId), TREATING).andExpect(status().isNotFound());
        assertThat(body(perform(get("/api/v1/abdm/hiu/patients/" + uhid + "/consent-requests"), TREATING))
                .at("/0/status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void requestsNeedAVerifiedAbhaAndAShortRetentionAndADenialIsShown() throws Exception {
        linkDoctor();
        UUID noAbha = patient("Ravi", "Kumar", "M", 1974, newMobile());
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> ask = new LinkedHashMap<>(Map.of("uhid", uhid(noAbha), "purpose", "CAREMGT",
                "hiTypes", List.of("Prescription"), "from", today.minusYears(1).toString(), "to", today.toString(),
                "keepUntil", today.plusDays(3).toString()));
        postJson("/api/v1/abdm/hiu/consent-requests", ask, TREATING).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ABHA_ADDRESS_REQUIRED"));

        String address = patientWithAbha();
        ask.put("uhid", uhidOf(address));
        ask.put("keepUntil", today.plusDays(90).toString());
        postJson("/api/v1/abdm/hiu/consent-requests", ask, TREATING).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BAD_RETENTION"));
        ask.put("keepUntil", today.plusDays(3).toString());
        ask.put("hiTypes", List.of("XRayFilm"));
        postJson("/api/v1/abdm/hiu/consent-requests", ask, TREATING).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_HI_TYPE"));
        ask.put("hiTypes", List.of("Prescription"));
        postJson("/api/v1/abdm/hiu/consent-requests", ask, RECEPTION).andExpect(status().isForbidden());

        postJson("/api/v1/abdm/hiu/consent-requests", ask, TREATING).andExpect(status().isAccepted());
        FakeAbdm.Received init = ABDM.awaitMatching("/consent/v3/request/init",
                r -> r.body().at("/consent/patient/id").asText().equals(address));
        UUID consentRequestId = UUID.randomUUID();
        callback("/api/v3/hiu/consent/request/on-init", UUID.randomUUID(), Map.of(
                "consentRequest", Map.of("id", consentRequestId.toString()),
                "response", Map.of("requestId", init.header("REQUEST-ID")))).andExpect(status().isAccepted());
        UUID notifyId = UUID.randomUUID();
        callback("/api/v3/hiu/consent/request/notify", notifyId, Map.of("notification", Map.of(
                "consentRequestId", consentRequestId.toString(), "status", "DENIED"))).andExpect(status().isAccepted());
        ABDM.awaitMatching("/consent/v3/request/hiu/on-notify",
                r -> r.body().at("/response/requestId").asText().equals(notifyId.toString()));
        JsonNode consents = body(perform(get("/api/v1/abdm/hiu/patients/" + uhidOf(address) + "/consent-requests"), TREATING));
        assertThat(consents.at("/0/status").asText()).isEqualTo("DENIED");
        assertThat(consents.at("/0/artefacts")).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    /** A patient with a verified ABHA address; returns the address. */
    private String patientWithAbha() {
        linkDoctor();
        UUID patient = patient("Meera", "Pillai", "F", 1996, newMobile());
        String abha = newAbhaNumber();
        String address = "meera." + abha.substring(8) + "@sbx";
        verifiedAbha(patient, abha, address);
        return address;
    }

    private String uhidOf(String address) {
        return jdbc.sql("SELECT uhid FROM patient.patient WHERE abha_address = :a").param("a", address)
                .query(String.class).single();
    }

    /** The test doctor signs in as {@code hiu.doctor}. */
    private void linkDoctor() {
        UUID doctor = doctor();
        jdbc.sql("UPDATE core.staff SET iam_subject = 'hiu.doctor' WHERE id = :id AND iam_subject IS NULL")
                .param("id", doctor).update();
    }

    private static Map<String, Object> consentDetail(UUID artefact, String address, LocalDate today) {
        return Map.of(
                "schemaVersion", "v3",
                "consentId", artefact.toString(),
                "createdAt", Instant.now().toString(),
                "patient", Map.of("id", address),
                "careContexts", List.of(Map.of("patientReference", "CC-PAT-77", "careContextReference", "CC-OPD-1"),
                        Map.of("patientReference", "CC-PAT-77", "careContextReference", "CC-OPD-2")),
                "purpose", Map.of("text", "Care Management", "code", "CAREMGT"),
                "hip", Map.of("id", "IN2910000099", "name", "City Care Hospital"),
                "hiu", Map.of("id", HIP_ID),
                "hiTypes", List.of("OPConsultation"),
                "permission", Map.of("accessMode", "VIEW",
                        "dateRange", Map.of("from", today.minusYears(2) + "T00:00:00.000Z", "to", today + "T00:00:00.000Z"),
                        "dataEraseAt", today.plusDays(5) + "T00:00:00.000Z",
                        "frequency", Map.of("unit", "HOUR", "value", 1, "repeats", 0)));
    }

    /** What the other hospital sends: an OP consultation with a medicine and a lab value. */
    private static Bundle consultation() {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setIdentifier(new Identifier().setSystem("https://citycare.test/bundle").setValue(UUID.randomUUID().toString()));
        bundle.setTimestamp(new Date());
        Composition composition = new Composition();
        composition.setId(UUID.randomUUID().toString());
        composition.getMeta().addProfile("https://nrces.in/ndhm/fhir/r4/StructureDefinition/OPConsultRecord");
        composition.setStatus(Composition.CompositionStatus.FINAL);
        composition.setType(new CodeableConcept().addCoding(new Coding("http://snomed.info/sct", "371530004",
                "Clinical consultation report")));
        composition.setTitle("OP Consultation");
        composition.setDate(Date.from(Instant.parse("2026-10-02T05:00:00Z")));
        org.hl7.fhir.r4.model.Practitioner doctor = new org.hl7.fhir.r4.model.Practitioner();
        doctor.setId(UUID.randomUUID().toString());
        doctor.addName().setText("Dr Kavita Rao");
        composition.addAuthor(new Reference("urn:uuid:" + doctor.getIdPart()));
        MedicationRequest medicine = new MedicationRequest();
        medicine.setId(UUID.randomUUID().toString());
        medicine.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        medicine.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        medicine.setMedication(new CodeableConcept().setText("Tab Metformin 500 mg"));
        medicine.addDosageInstruction().setText("1 tab twice a day after food");
        Observation hba1c = new Observation();
        hba1c.setId(UUID.randomUUID().toString());
        hba1c.setStatus(Observation.ObservationStatus.FINAL);
        hba1c.setCode(new CodeableConcept().setText("HbA1c").addCoding(new Coding("http://loinc.org", "4548-4",
                "Hemoglobin A1c/Hemoglobin.total in Blood")));
        hba1c.setValue(new Quantity().setValue(8.1).setUnit("%"));
        hba1c.addInterpretation(new CodeableConcept().addCoding(new Coding(
                "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation", "H", "High")));
        hba1c.addReferenceRange().setLow(new Quantity().setValue(4.0).setUnit("%")).setHigh(new Quantity().setValue(5.6).setUnit("%"));
        composition.addSection().setTitle("Medications").addEntry(new Reference("urn:uuid:" + medicine.getIdPart()));
        composition.addSection().setTitle("Investigations").addEntry(new Reference("urn:uuid:" + hba1c.getIdPart()));
        bundle.addEntry().setFullUrl("urn:uuid:" + composition.getIdPart()).setResource(composition);
        bundle.addEntry().setFullUrl("urn:uuid:" + doctor.getIdPart()).setResource(doctor);
        bundle.addEntry().setFullUrl("urn:uuid:" + medicine.getIdPart()).setResource(medicine);
        bundle.addEntry().setFullUrl("urn:uuid:" + hba1c.getIdPart()).setResource(hba1c);
        return bundle;
    }

    private static String md5(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
