package com.syncra.hims.abdm.carecontext;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.fhir.EncounterRecord;
import com.syncra.hims.abdm.fhir.HealthRecords;
import com.syncra.hims.abdm.fhir.HiType;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmHeaders;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.abdm.gateway.GatewayMessageLog;
import com.syncra.hims.abdm.gateway.Redact;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Milestone 2, HIP-initiated linking: every encounter with shareable records becomes a care
 * context, linked to the patient's verified ABHA so it shows in their PHR app.
 *
 * <pre>
 *  staff: link encounter ──► care context PENDING (awaiting token)
 *          ├─ cached link token? ──► POST /hip/v3/link/carecontext (X-LINK-TOKEN) ──► on_carecontext ──► LINKED
 *          └─ else POST /v3/token/generate-token ──► on-generate-token (token cached) ──► link call as above
 * </pre>
 * Callbacks are matched to the outbound call by REQUEST-ID (stored with the patient id), never
 * by identifiers in the callback body.
 */
@Service
public class CareContextService {

    private static final Logger log = LoggerFactory.getLogger(CareContextService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final AbdmProperties properties;
    private final HealthRecords records;
    private final CareContextRepository careContexts;
    private final PatientDirectory patients;
    private final LinkTokenStore tokens;
    private final AbdmClient abdm;
    private final GatewayMessageLog messages;
    private final AbdmTasks tasks;
    private final AuditService audit;
    private final HospitalTime time;
    private final TransactionTemplate transaction;

    public CareContextService(AbdmProperties properties, HealthRecords records, CareContextRepository careContexts,
                              PatientDirectory patients,
                              LinkTokenStore tokens, AbdmClient abdm, GatewayMessageLog messages, AbdmTasks tasks,
                              AuditService audit, HospitalTime time, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.records = records;
        this.careContexts = careContexts;
        this.patients = patients;
        this.tokens = tokens;
        this.abdm = abdm;
        this.messages = messages;
        this.tasks = tasks;
        this.audit = audit;
        this.time = time;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ staff

    /** Creates (or widens) the encounter's care context and starts linking it to the patient's ABHA. */
    @Transactional
    public CareContext link(UUID encounterId) {
        if (!properties.enabled()) {
            throw AbdmCallException.disabled();
        }
        EncounterRecord record = records.record(encounterId)
                .orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
        Set<HiType> types = records.available(record);
        if (types.isEmpty()) {
            throw ApiException.unprocessable("NOTHING_TO_LINK", record.isInpatient() && !record.isDischarged()
                    ? "Inpatient records are linked after discharge"
                    : "This encounter has no consultation, prescription or discharge record to share yet");
        }
        PatientDirectory.Patient patient = patients.find(record.patient().id()).orElseThrow();
        if (!patient.hasVerifiedAbha()) {
            throw ApiException.unprocessable("ABHA_NOT_VERIFIED",
                    "Verify the patient's ABHA before linking records (patient " + patient.uhid() + ")");
        }
        CareContext context = careContexts.upsertPending(patient.id(), encounterId, display(record),
                types.stream().map(HiType::code).sorted().toList());
        audit.record(Action.SHARE, "care_context", context.id(), patient.id(), Map.of("action", "link_requested",
                "hiTypes", context.hiTypes()));
        if ("PENDING".equals(context.linkStatus()) && context.linkRequestId() == null) {
            tasks.afterCommit("link-care-contexts", () -> linkPending(patient.id()));
        }
        return context;
    }

    public List<CareContext> forPatient(UUID patientId) {
        return careContexts.forPatient(patientId);
    }

    // ------------------------------------------------------------------ linking

    /** Links whatever is waiting for this patient: with a cached token at once, else after requesting one. */
    public void linkPending(UUID patientId) {
        if (!careContexts.hasAwaitingLink(patientId)) {
            return;
        }
        tokens.get(patientId).ifPresentOrElse(
                token -> sendLink(patientId, token, true),
                () -> requestLinkToken(patientId));
    }

    void requestLinkToken(UUID patientId) {
        PatientDirectory.Patient patient = patients.find(patientId).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        if (patient.abhaNumber() != null) {
            body.put("abhaNumber", AbhaNumbers.hyphenated(patient.abhaNumber()));
        }
        if (patient.abhaAddress() != null) {
            body.put("abhaAddress", patient.abhaAddress());
        }
        body.put("name", patient.fullName());
        body.put("gender", patient.gender());
        if (patient.dateOfBirth() != null) {
            body.put("yearOfBirth", patient.dateOfBirth().getYear());
        }
        try {
            abdm.gatewayPost(AbdmPaths.GENERATE_LINK_TOKEN, body, abdm.asHip(Call.forPatient(patientId)));
        } catch (AbdmCallException e) {
            careContexts.failAwaiting(patientId, "Link token request failed: " + e.getMessage());
            throw e;
        }
    }

    /** {@code on-generate-token}: ABDM's answer to our token request, matched by REQUEST-ID. */
    public void onLinkToken(JsonNode body) {
        UUID requestId = responseRequestId(body);
        GatewayMessageLog.OutboundCall call = requestId == null ? null : messages.outboundCall(requestId)
                .filter(c -> AbdmPaths.GENERATE_LINK_TOKEN.equals(c.apiPath()) && c.patientId() != null)
                .orElse(null);
        if (call == null) {
            log.warn("Ignoring link token for an unknown request");
            return;
        }
        UUID patientId = call.patientId();
        JsonNode error = body.path("error");
        String token = text(body, "linkToken");
        if (!error.isMissingNode() && !error.isNull() || token == null) {
            String message = error.isMissingNode() || error.isNull() ? "ABDM issued no link token"
                    : "ABDM: " + Redact.text(text(error, "message"));
            careContexts.failAwaiting(patientId, message);
            return;
        }
        tokens.put(patientId, token);
        tasks.afterCommit("link-care-contexts", () -> sendLink(patientId, token, false));
    }

    private void sendLink(UUID patientId, String token, boolean tokenFromCache) {
        UUID requestId = UUID.randomUUID();
        List<CareContext> claimed = transaction.execute(s -> careContexts.claimAwaitingLink(patientId, requestId));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        PatientDirectory.Patient patient = patients.find(patientId).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        if (patient.abhaNumber() != null) {
            body.put("abhaNumber", AbhaNumbers.hyphenated(patient.abhaNumber()));
        }
        if (patient.abhaAddress() != null) {
            body.put("abhaAddress", patient.abhaAddress());
        }
        body.put("patient", CareContextGroups.byHiType(patient.uhid(), patient.fullName(), claimed));
        Call call = new Call(requestId, patientId, null, Map.of(AbdmHeaders.LINK_TOKEN, token));
        try {
            abdm.gatewayPost(AbdmPaths.LINK_CARE_CONTEXT, body, abdm.asHip(call));
        } catch (AbdmCallException e) {
            if (tokenFromCache && e.upstreamStatus() >= 400 && e.upstreamStatus() < 500) {
                // Most likely an expired token: drop it and start over with a fresh one, once.
                tokens.evict(patientId);
                careContexts.requeue(requestId);
                requestLinkToken(patientId);
                return;
            }
            careContexts.markFailed(requestId, e.getMessage());
            throw e;
        }
    }

    /** {@code on_carecontext}: the outcome of a link call, matched by REQUEST-ID. */
    public void onLinkResult(JsonNode body) {
        UUID requestId = responseRequestId(body);
        if (requestId == null) {
            log.warn("Ignoring link result without response.requestId");
            return;
        }
        JsonNode error = body.path("error");
        int updated;
        if (!error.isMissingNode() && !error.isNull()) {
            updated = careContexts.markFailed(requestId, "ABDM: " + Redact.text(text(error, "message")));
        } else {
            List<CareContext> linked = careContexts.byLinkRequest(requestId);
            String address = AbhaNumbers.address(text(body, "abhaAddress"));
            if (address == null && !linked.isEmpty()) {
                address = patients.find(linked.get(0).patientId()).map(PatientDirectory.Patient::abhaAddress)
                        .orElse(null);
            }
            updated = careContexts.markLinked(requestId, address != null ? address : "unknown@abdm", time.now());
        }
        if (updated == 0) {
            log.info("Link result matched no pending care context (already settled or re-queued)");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** How the care context is shown to the patient in the PHR app. */
    public String display(EncounterRecord record) {
        EncounterRecord.Encounter e = record.encounter();
        if (record.admission() != null) {
            Instant end = record.admission().dischargedAt();
            return "Inpatient stay " + record.admission().admissionNumber() + " — " + e.departmentName() + " — "
                    + date(record.admission().admittedAt()) + (end == null ? "" : " to " + date(end));
        }
        String kind = "EMERGENCY".equals(e.type()) ? "Emergency visit" : "OP consultation";
        return kind + " — " + e.departmentName() + " — " + date(e.startedAt());
    }

    private String date(Instant instant) {
        return DATE.format(instant.atZone(time.zone()));
    }

    static UUID responseRequestId(JsonNode body) {
        String value = text(body.path("response"), "requestId");
        if (value == null) {
            value = text(body.path("resp"), "requestId");
        }
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
