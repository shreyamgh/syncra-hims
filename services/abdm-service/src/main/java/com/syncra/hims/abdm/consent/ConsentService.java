package com.syncra.hims.abdm.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.callback.CallbackAck;
import com.syncra.hims.abdm.callback.Json;
import com.syncra.hims.abdm.carecontext.CareContextRepository;
import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent notifications to this HIP ({@code /consent/request/hip/notify}). A GRANTED artefact
 * is stored verbatim with the care contexts it covers; REVOKED / EXPIRED / DENIED switch it off,
 * which also stops any transfer that has not started. Every notification is acknowledged with
 * {@code on-notify}, with an error when the artefact cannot be accepted.
 */
@Service
public class ConsentService {

    static final Set<String> STATUSES = Set.of("GRANTED", "REVOKED", "EXPIRED", "DENIED");
    static final Set<String> PURPOSES = Set.of("CAREMGT", "BTG", "PUBHLTH", "HPAYMT", "DSRCH", "PATRQT");
    static final Set<String> ACCESS_MODES = Set.of("VIEW", "STORE", "QUERY", "STREAM");
    static final Set<String> HI_TYPES = Set.of("OPConsultation", "Prescription", "DischargeSummary",
            "DiagnosticReport", "ImmunizationRecord", "HealthDocumentRecord", "WellnessRecord", "Invoice");

    private final ConsentRepository consents;
    private final CareContextRepository careContexts;
    private final PatientDirectory patients;
    private final CallbackAck acks;
    private final AbdmTasks tasks;
    private final AuditService audit;
    private final AbdmProperties properties;
    private final ObjectMapper json;

    public ConsentService(ConsentRepository consents, CareContextRepository careContexts, PatientDirectory patients,
                          CallbackAck acks, AbdmTasks tasks, AuditService audit, AbdmProperties properties,
                          ObjectMapper json) {
        this.consents = consents;
        this.careContexts = careContexts;
        this.patients = patients;
        this.acks = acks;
        this.tasks = tasks;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public void onNotify(JsonNode body, UUID requestId) {
        JsonNode notification = body.path("notification");
        String status = Json.text(notification, "status");
        UUID consentId = Json.uuid(notification, "consentId");
        if (consentId == null) {
            consentId = Json.uuid(notification, "consentDetail.consentId");
        }
        UUID ackConsentId = consentId;
        String problem = status == null || !STATUSES.contains(status) ? "Unknown consent status"
                : consentId == null ? "Missing consentId" : null;
        if (problem == null) {
            problem = "GRANTED".equals(status) ? storeGranted(consentId, notification) : changeStatus(consentId, status);
        }
        String error = problem;
        tasks.afterCommit("consent-on-notify", () -> acknowledge(requestId, ackConsentId, error));
    }

    private void acknowledge(UUID requestId, UUID consentId, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (error == null) {
            body.put("acknowledgement", Map.of("status", "OK", "consentId", consentId.toString()));
        } else {
            body.put("error", CallbackAck.error(CallbackAck.INVALID_REQUEST, error));
        }
        acks.send(AbdmPaths.CONSENT_ON_NOTIFY, requestId, body, null);
    }

    /** @return null when stored, else why the artefact was refused */
    private String storeGranted(UUID consentId, JsonNode notification) {
        JsonNode detail = notification.path("consentDetail");
        if (detail.isMissingNode() || detail.isNull()) {
            return "Missing consentDetail";
        }
        String hipId = Json.text(detail, "hip.id");
        if (hipId != null && properties.hipId() != null && !hipId.equals(properties.hipId())) {
            return "Consent is addressed to another HIP";
        }
        String purpose = Json.text(detail, "purpose.code");
        List<String> hiTypes = Json.texts(detail, "hiTypes");
        String accessMode = Json.text(detail, "permission.accessMode");
        Instant from = Json.instant(detail, "permission.dateRange.from");
        Instant to = Json.instant(detail, "permission.dateRange.to");
        Instant eraseAt = Json.instant(detail, "permission.dataEraseAt");
        if (purpose == null || !PURPOSES.contains(purpose)) {
            return "Unsupported purpose";
        }
        if (hiTypes.isEmpty() || !HI_TYPES.containsAll(hiTypes)) {
            return "Unsupported or missing hiTypes";
        }
        if (accessMode == null || !ACCESS_MODES.contains(accessMode)) {
            return "Unsupported accessMode";
        }
        if (from == null || to == null || eraseAt == null || to.isBefore(from)) {
            return "Invalid permission dates";
        }

        // Care contexts must be ours and belong to one patient (the one named by patientReference).
        List<UUID> covered = new ArrayList<>();
        UUID patientId = null;
        JsonNode contexts = detail.path("careContexts");
        for (JsonNode entry : contexts) {
            String reference = Json.text(entry, "careContextReference");
            String patientReference = Json.text(entry, "patientReference");
            CareContext context = reference == null ? null : careContexts.byReference(reference).orElse(null);
            if (context == null) {
                continue;
            }
            PatientDirectory.Patient owner = patients.find(context.patientId()).orElse(null);
            if (owner == null || (patientReference != null && !patientReference.equals(owner.uhid()))) {
                continue;
            }
            if (patientId != null && !patientId.equals(owner.id())) {
                return "Care contexts of more than one patient";
            }
            patientId = owner.id();
            covered.add(context.id());
        }
        String abhaAddress = AbhaNumbers.address(Json.text(detail, "patient.id"));
        if (patientId == null && abhaAddress != null) {
            patientId = patients.byAbha(null, abhaAddress).map(PatientDirectory.Patient::id).orElse(null);
        }

        String artefact;
        try {
            artefact = json.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "Unreadable consentDetail";
        }
        Integer frequencyValue = Json.present(detail, "permission.frequency.value")
                ? detail.at("/permission/frequency/value").asInt() : null;
        Integer frequencyRepeats = Json.present(detail, "permission.frequency.repeats")
                ? detail.at("/permission/frequency/repeats").asInt() : null;
        UUID row = consents.upsertGranted(new ConsentRepository.NewConsent(consentId,
                Json.uuid(detail, "consentRequestId"), patientId,
                Objects.requireNonNullElse(abhaAddress, Objects.requireNonNullElse(Json.text(detail, "patient.id"), "unknown")),
                Json.text(detail, "hiu.id"), Json.text(detail, "hiu.name"), Json.text(detail, "requester.name"),
                purpose, hiTypes, accessMode, from, to, eraseAt, Json.text(detail, "permission.frequency.unit"),
                frequencyValue, frequencyRepeats, Json.text(notification, "signature"), artefact));
        consents.replaceCareContexts(row, covered);
        audit.record(Action.CREATE, "consent_artefact", consentId, patientId,
                Map.of("status", "GRANTED", "purpose", purpose, "hiTypes", hiTypes, "careContexts", covered.size()));
        return null;
    }

    private String changeStatus(UUID consentId, String status) {
        if (consents.changeStatus(consentId, status) > 0) {
            UUID patientId = consents.byArtefactId(consentId).map(ConsentRepository.Consent::patientId).orElse(null);
            audit.record(Action.UPDATE, "consent_artefact", consentId, patientId, Map.of("status", status));
        }
        return null;
    }
}
