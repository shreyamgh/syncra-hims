package com.syncra.hims.abdm.hiu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.abdm.callback.Json;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.crypto.Fidelius;
import com.syncra.hims.abdm.crypto.Fidelius.KeyMaterial;
import com.syncra.hims.abdm.fhir.FhirJson;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.abdm.gateway.GatewayMessageLog;
import com.syncra.hims.abdm.gateway.GatewayMessageLog.Outcome;
import com.syncra.hims.abdm.gateway.Redact;
import com.syncra.hims.abdm.hiu.HiuRepository.Artefact;
import com.syncra.hims.abdm.hiu.HiuRepository.ConsentRequest;
import com.syncra.hims.abdm.hiu.HiuRepository.DataRequest;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * This hospital as a Health Information User (ABDM Milestone 3).
 *
 * <ol>
 *   <li>A doctor asks for the patient's consent ({@code consent/request/init}); ABDM answers with
 *       the consent request id ({@code on-init}).</li>
 *   <li>The patient approves in their ABHA app; ABDM notifies one artefact per HIP holding records
 *       ({@code hiu/consent/request/notify}), acknowledged with {@code on-notify}; each artefact is
 *       fetched ({@code consent/fetch} → {@code on-fetch}).</li>
 *   <li>For each artefact, records are requested ({@code health-information/request}) with a
 *       one-time Curve25519 key and a one-time data-push URL ({@code on-request} gives the
 *       transaction id).</li>
 *   <li>The HIP pushes the encrypted FHIR documents to that URL. They are decrypted, their
 *       checksum verified, stored until the consent's erase time, and the transfer is reported
 *       ({@code health-information/notify}).</li>
 * </ol>
 * Revocation or expiry deletes what was received. Every read of a received record is audited.
 */
@Service
public class HiuService {

    private static final Logger log = LoggerFactory.getLogger(HiuService.class);
    private static final DateTimeFormatter ABDM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PAGES = 100;
    private static final int MAX_ENTRIES_PER_PAGE = 100;

    static final Map<String, String> PURPOSES = Map.of(
            "CAREMGT", "Care Management",
            "BTG", "Break the Glass",
            "PUBHLTH", "Public Health",
            "HPAYMT", "Healthcare Payment",
            "DSRCH", "Disease Specific Healthcare Research",
            "PATRQT", "Self Requested");
    static final List<String> HI_TYPES = List.of("OPConsultation", "Prescription", "DischargeSummary",
            "DiagnosticReport", "ImmunizationRecord", "HealthDocumentRecord", "WellnessRecord", "Invoice");
    private static final Set<String> NOTIFY_STATUSES = Set.of("GRANTED", "DENIED", "EXPIRED", "REVOKED");

    /** A doctor's request, as entered. Dates are hospital-local days. */
    public record NewConsentRequest(String uhid, String purpose, List<String> hiTypes, Instant from, Instant to,
                                    Instant eraseAt) {
    }

    private record Requester(UUID id, String name, String registration) {
    }

    private final HiuRepository repository;
    private final HiuKeyStore keys;
    private final PatientDirectory patients;
    private final AbdmClient abdm;
    private final AbdmTasks tasks;
    private final GatewayMessageLog messages;
    private final AuditService audit;
    private final AbdmProperties properties;
    private final HospitalTime time;
    private final ObjectMapper json;
    private final FhirJson fhir;
    private final JdbcClient jdbc;

    public HiuService(HiuRepository repository, HiuKeyStore keys, PatientDirectory patients, AbdmClient abdm,
                      AbdmTasks tasks, GatewayMessageLog messages, AuditService audit, AbdmProperties properties,
                      HospitalTime time, ObjectMapper json, FhirJson fhir, JdbcClient jdbc) {
        this.repository = repository;
        this.keys = keys;
        this.patients = patients;
        this.abdm = abdm;
        this.tasks = tasks;
        this.messages = messages;
        this.audit = audit;
        this.properties = properties;
        this.time = time;
        this.json = json;
        this.fhir = fhir;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 1. consent request

    @Transactional
    public ConsentRequest requestConsent(String staffSubject, NewConsentRequest request) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ABDM_DISABLED", "ABDM integration is not enabled");
        }
        Requester requester = requester(staffSubject);
        PatientDirectory.Patient patient = patients.byUhid(request.uhid())
                .orElseThrow(() -> ApiException.notFound("Patient", request.uhid()));
        if (!patient.hasVerifiedAbha() || patient.abhaAddress() == null) {
            throw ApiException.unprocessable("ABHA_ADDRESS_REQUIRED",
                    "Verify the patient's ABHA address first: consent is asked for in their ABHA app");
        }
        if (request.purpose() == null || !PURPOSES.containsKey(request.purpose())) {
            throw ApiException.unprocessable("UNKNOWN_PURPOSE", "Purpose must be one of " + PURPOSES.keySet());
        }
        List<String> types = request.hiTypes() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.hiTypes()));
        if (types.isEmpty() || !HI_TYPES.containsAll(types)) {
            throw ApiException.unprocessable("UNKNOWN_HI_TYPE", "Choose record types among " + HI_TYPES);
        }
        Instant now = time.now();
        if (request.from() == null || request.to() == null || !request.from().isBefore(request.to())) {
            throw ApiException.unprocessable("BAD_PERIOD", "The records' period must start before it ends");
        }
        if (request.to().isAfter(now.plus(1, ChronoUnit.DAYS))) {
            throw ApiException.unprocessable("BAD_PERIOD", "The records' period cannot end in the future");
        }
        if (request.eraseAt() == null || !request.eraseAt().isAfter(now)
                || request.eraseAt().isAfter(now.plus(properties.hiu().maxRetentionDays(), ChronoUnit.DAYS))) {
            throw ApiException.unprocessable("BAD_RETENTION", "Keep the records for at most "
                    + properties.hiu().maxRetentionDays() + " days");
        }
        UUID gatewayRequestId = UUID.randomUUID();
        UUID id = repository.insertConsentRequest(gatewayRequestId, patient.id(), patient.abhaAddress(),
                request.purpose(), types, request.from(), request.to(), request.eraseAt(), requester.id());
        audit.record(Action.CREATE, "hiu_consent_request", id, patient.id(),
                Map.of("purpose", request.purpose(), "hiTypes", types));
        tasks.afterCommit("hiu-consent-request", () -> sendConsentRequest(id, requester));
        return repository.request(id).orElseThrow();
    }

    private void sendConsentRequest(UUID id, Requester requester) {
        ConsentRequest r = repository.request(id).orElseThrow();
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("accessMode", "VIEW");
        permission.put("dateRange", Map.of("from", ABDM_TIME.format(r.from()), "to", ABDM_TIME.format(r.to())));
        permission.put("dataEraseAt", ABDM_TIME.format(r.eraseAt()));
        permission.put("frequency", Map.of("unit", "HOUR", "value", 1, "repeats", 0));
        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put("purpose", Map.of("text", PURPOSES.get(r.purpose()), "code", r.purpose(),
                "refUri", "http://terminology.hl7.org/ValueSet/v3-PurposeOfUse"));
        consent.put("patient", Map.of("id", r.abhaAddress()));
        consent.put("hiu", Map.of("id", properties.hipId()));
        consent.put("requester", Map.of("name", requester.name(), "identifier", Map.of(
                "type", "REGNO", "value", requester.registration(), "system", "https://www.mciindia.org")));
        consent.put("hiTypes", r.hiTypes());
        consent.put("permission", permission);
        try {
            abdm.gatewayPost(AbdmPaths.HIU_CONSENT_REQUEST_INIT, Map.of("consent", consent),
                    abdm.asHiu(new Call(r.gatewayRequestId(), r.patientId(), null, Map.of())));
        } catch (AbdmCallException e) {
            repository.setRequestStatus(id, "FAILED", "ABDM did not accept the request (" + e.code() + ")");
        }
    }

    @Transactional
    public void onConsentInit(JsonNode body) {
        UUID requestId = Json.uuid(body, "response.requestId");
        Optional<ConsentRequest> found = requestId == null ? Optional.empty()
                : repository.requestByGatewayRequestId(requestId);
        if (found.isEmpty()) {
            log.warn("Ignoring consent on-init for an unknown request");
            return;
        }
        ConsentRequest r = found.get();
        String error = errorMessage(body);
        UUID consentRequestId = Json.uuid(body, "consentRequest.id");
        if (error != null || consentRequestId == null) {
            repository.setRequestStatus(r.id(), "FAILED", error != null ? error : "ABDM gave no consent request id");
            return;
        }
        repository.acceptConsentRequest(r.id(), consentRequestId);
    }

    // ------------------------------------------------------------------ 2. the patient's decision

    @Transactional
    public void onConsentNotify(JsonNode body, UUID inboundRequestId) {
        JsonNode notification = body.path("notification");
        UUID consentRequestId = Json.uuid(notification, "consentRequestId");
        String status = Json.text(notification, "status");
        List<UUID> artefactIds = new ArrayList<>();
        for (JsonNode artefact : notification.path("consentArtefacts")) {
            UUID id = Json.uuid(artefact, "id");
            if (id != null) {
                artefactIds.add(id);
            }
        }
        Optional<ConsentRequest> found = consentRequestId == null ? Optional.empty()
                : repository.requestByConsentRequestId(consentRequestId);
        String problem = found.isEmpty() ? "Unknown consent request"
                : status == null || !NOTIFY_STATUSES.contains(status) ? "Unknown consent status"
                : "GRANTED".equals(status) && artefactIds.isEmpty() ? "No consent artefacts" : null;
        List<UUID> acknowledged = new ArrayList<>();
        if (problem == null) {
            ConsentRequest r = found.get();
            switch (status) {
                case "GRANTED" -> {
                    artefactIds.forEach(id -> repository.upsertArtefact(id, r.id(), "GRANTED"));
                    repository.setRequestStatus(r.id(), "GRANTED", null);
                    acknowledged.addAll(artefactIds);
                    List<UUID> toFetch = List.copyOf(artefactIds);
                    tasks.afterCommit("hiu-consent-fetch", () -> toFetch.forEach(this::fetch));
                }
                case "DENIED" -> repository.setRequestStatus(r.id(), "DENIED", null);
                default -> { // EXPIRED, REVOKED: stop and erase what was received
                    List<UUID> ours = repository.artefactIds(r.id());
                    List<UUID> affected = artefactIds.isEmpty() ? ours
                            : artefactIds.stream().filter(ours::contains).toList();
                    repository.setArtefactStatus(affected, status);
                    if (affected.containsAll(ours)) {
                        repository.setRequestStatus(r.id(), status, null);
                    }
                    int erased = repository.eraseRecords(affected);
                    if (erased > 0) {
                        audit.record(Action.DELETE, "hiu_health_record", r.id(), r.patientId(),
                                Map.of("records", erased, "reason", "consent " + status.toLowerCase()));
                    }
                    acknowledged.addAll(affected);
                }
            }
            audit.record(Action.UPDATE, "hiu_consent_request", r.id(), r.patientId(), Map.of("status", status));
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        if (problem == null) {
            reply.put("acknowledgement", acknowledged.stream()
                    .map(id -> Map.of("status", "OK", "consentId", id.toString())).toList());
        } else {
            reply.put("error", Map.of("code", 1400, "message", problem));
        }
        tasks.afterCommit("hiu-consent-on-notify", () -> acknowledge(AbdmPaths.HIU_CONSENT_ON_NOTIFY,
                inboundRequestId, reply));
    }

    /** Asks ABDM for an artefact's details (which HIP, care contexts, record types, dates). */
    void fetch(UUID consentId) {
        try {
            abdm.gatewayPost(AbdmPaths.HIU_CONSENT_FETCH, Map.of("consentId", consentId.toString()),
                    abdm.asHiu(Call.plain()));
        } catch (AbdmCallException e) {
            log.warn("Consent fetch for {} failed: {}", consentId, e.code());
        }
    }

    @Transactional
    public void onConsentFetch(JsonNode body) {
        String error = errorMessage(body);
        JsonNode consent = body.path("consent");
        JsonNode detail = consent.path("consentDetail");
        UUID consentId = Json.uuid(detail, "consentId");
        Optional<Artefact> found = consentId == null ? Optional.empty() : repository.artefact(consentId);
        if (error != null || found.isEmpty()) {
            log.warn("Ignoring consent on-fetch: {}", error != null ? error : "unknown artefact");
            return;
        }
        String hiuId = Json.text(detail, "hiu.id");
        if (hiuId != null && properties.hipId() != null && !hiuId.equals(properties.hipId())) {
            log.warn("Ignoring consent artefact {} issued to another HIU", consentId);
            return;
        }
        ConsentRequest request = repository.request(found.get().requestRowId()).orElseThrow();
        String status = Optional.ofNullable(Json.text(consent, "status")).orElse("GRANTED");
        if (!NOTIFY_STATUSES.contains(status)) {
            status = "DENIED";
        }
        List<String> types = Json.texts(detail, "hiTypes").stream().filter(HI_TYPES::contains).toList();
        Instant from = Json.instant(detail, "permission.dateRange.from");
        Instant to = Json.instant(detail, "permission.dateRange.to");
        Instant eraseAt = Json.instant(detail, "permission.dataEraseAt");
        // The patient may shorten what the doctor asked for, never lengthen it.
        if (eraseAt == null || eraseAt.isAfter(request.eraseAt())) {
            eraseAt = request.eraseAt();
        }
        String careContexts;
        try {
            careContexts = json.writeValueAsString(detail.path("careContexts").isArray() ? detail.path("careContexts")
                    : json.createArrayNode());
        } catch (JsonProcessingException e) {
            careContexts = "[]";
        }
        repository.storeArtefactDetail(consentId, status, Json.text(detail, "hip.id"), Json.text(detail, "hip.name"),
                careContexts, types, from != null ? from : request.from(), to != null ? to : request.to(), eraseAt,
                time.now());
        if ("GRANTED".equals(status) && properties.hiu().canReceive()) {
            startDataRequest(repository.artefact(consentId).orElseThrow());
        }
    }

    // ------------------------------------------------------------------ 3. asking the HIP for the records

    /** A new data request for a granted artefact (after a failed or finished one). */
    @Transactional
    public DataRequest requestDataAgain(UUID consentId, UUID patientId) {
        Artefact artefact = repository.artefact(consentId).filter(a -> a.patientId().equals(patientId))
                .orElseThrow(() -> ApiException.notFound("Consent", consentId));
        if (artefact.fetchedAt() == null) {
            fetch(consentId); // ask again now: a refusal below would roll back anything deferred
            throw ApiException.conflict("CONSENT_DETAILS_PENDING", "Waiting for the consent's details from ABDM; try again shortly");
        }
        if (!artefact.isUsable(time.now())) {
            throw ApiException.conflict("CONSENT_NOT_ACTIVE", "This consent is no longer active");
        }
        boolean inProgress = repository.dataRequests(List.of(consentId)).stream().anyMatch(DataRequest::isOpen);
        if (inProgress) {
            throw ApiException.conflict("TRANSFER_IN_PROGRESS", "Records are already on their way");
        }
        return startDataRequest(artefact);
    }

    private DataRequest startDataRequest(Artefact artefact) {
        if (!properties.hiu().canReceive()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HIU_NOT_CONFIGURED",
                    "This hospital has no public URL for receiving records (syncra.abdm.hiu.public-url)");
        }
        KeyMaterial key = Fidelius.generateKeyMaterial();
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID gatewayRequestId = UUID.randomUUID();
        UUID id = repository.insertDataRequest(gatewayRequestId, artefact.consentId(), sha256(token), artefact.from(),
                artefact.to());
        Instant expiresAt = time.now().plus(properties.hiu().keyValidity());
        keys.put(id, key, expiresAt);
        tasks.afterCommit("hiu-health-information-request",
                () -> sendDataRequest(id, gatewayRequestId, artefact, token, key, expiresAt));
        return repository.dataRequest(id).orElseThrow();
    }

    private void sendDataRequest(UUID id, UUID gatewayRequestId, Artefact artefact, String token, KeyMaterial key,
                                 Instant expiresAt) {
        String base = properties.hiu().publicUrl().toString().replaceAll("/+$", "");
        Map<String, Object> hiRequest = new LinkedHashMap<>();
        hiRequest.put("consent", Map.of("id", artefact.consentId().toString()));
        hiRequest.put("dateRange", Map.of("from", ABDM_TIME.format(artefact.from()), "to", ABDM_TIME.format(artefact.to())));
        hiRequest.put("dataPushUrl", base + AbdmPaths.HIU_DATA_PUSH_ROOT + "/" + token);
        hiRequest.put("keyMaterial", Map.of(
                "cryptoAlg", Fidelius.ALGORITHM,
                "curve", Fidelius.CURVE,
                "dhPublicKey", Map.of("expiry", ABDM_TIME.format(expiresAt), "parameters", Fidelius.KEY_PARAMETERS,
                        "keyValue", key.x509PublicKey()),
                "nonce", key.nonce()));
        try {
            abdm.gatewayPost(AbdmPaths.HIU_HEALTH_INFORMATION_REQUEST, Map.of("hiRequest", hiRequest),
                    abdm.asHiu(new Call(gatewayRequestId, artefact.patientId(), null, Map.of())));
        } catch (AbdmCallException e) {
            keys.forget(id);
            repository.finishDataRequest(id, "FAILED", "ABDM did not accept the request (" + e.code() + ")", time.now());
        }
    }

    @Transactional
    public void onHealthInformationRequest(JsonNode body) {
        UUID requestId = Json.uuid(body, "response.requestId");
        Optional<DataRequest> found = requestId == null ? Optional.empty()
                : repository.dataRequestByGatewayRequestId(requestId);
        if (found.isEmpty()) {
            log.warn("Ignoring health-information on-request for an unknown request");
            return;
        }
        DataRequest request = found.get();
        String error = errorMessage(body);
        UUID transactionId = Json.uuid(body, "hiRequest.transactionId");
        if (error != null || transactionId == null || "ERRORED".equals(Json.text(body, "hiRequest.sessionStatus"))) {
            keys.forget(request.id());
            repository.finishDataRequest(request.id(), "FAILED",
                    error != null ? error : "ABDM did not start the transfer", time.now());
            return;
        }
        if (request.isOpen()) {
            repository.acknowledgeDataRequest(request.id(), transactionId);
        }
    }

    // ------------------------------------------------------------------ 4. the HIP's push

    /**
     * One page of encrypted records pushed to a data-push URL. Problems with single records are
     * reported to the HIP, not thrown; a transfer that cannot go on is marked failed (kept on refusal).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void receive(String token, JsonNode body) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw ApiException.notFound("Data push URL", "?");
        }
        DataRequest request = repository.dataRequestByToken(sha256(token))
                .orElseThrow(() -> ApiException.notFound("Data push URL", "?"));
        if (!request.isOpen()) {
            throw ApiException.conflict("TRANSFER_CLOSED", "This transfer is already complete");
        }
        UUID transactionId = Json.uuid(body, "transactionId");
        if (transactionId == null || request.transactionId() != null && !transactionId.equals(request.transactionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_TRANSACTION", "transactionId does not match the request");
        }
        int page = body.path("pageNumber").asInt(1);
        int pageCount = body.path("pageCount").asInt(1);
        JsonNode entries = body.path("entries");
        if (page < 1 || pageCount < page || pageCount > MAX_PAGES || !entries.isArray() || entries.size() > MAX_ENTRIES_PER_PAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "pageNumber, pageCount or entries out of range");
        }
        String senderKey = Json.text(body, "keyMaterial.dhPublicKey.keyValue");
        String senderNonce = Json.text(body, "keyMaterial.nonce");
        if (!Fidelius.isUsablePublicKey(senderKey) || !Fidelius.isUsableNonce(senderNonce)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KEY_MATERIAL", "keyMaterial is missing or unusable");
        }
        Instant now = time.now();
        Artefact artefact = repository.artefact(request.consentId()).orElseThrow();
        KeyMaterial key = keys.get(request.id()).orElse(null);
        if (key == null || !artefact.isUsable(now)) {
            String reason = key == null ? "The decryption key expired before the records arrived"
                    : "The consent is no longer active";
            keys.forget(request.id());
            repository.finishDataRequest(request.id(), "FAILED", reason, now);
            throw new ApiException(HttpStatus.GONE, key == null ? "KEY_EXPIRED" : "CONSENT_NOT_ACTIVE", reason);
        }
        if (request.transactionId() == null) {
            repository.acknowledgeDataRequest(request.id(), transactionId); // the push overtook on-request
        }

        Map<String, String> failures = new LinkedHashMap<>();
        int stored = 0;
        for (JsonNode entry : entries) {
            String reference = Optional.ofNullable(Json.text(entry, "careContextReference")).orElse("(none)");
            String problem = null;
            String document = null;
            try {
                document = Fidelius.decrypt(Json.text(entry, "content"), key.nonce(), senderNonce, key.privateKey(),
                        senderKey);
            } catch (RuntimeException e) {
                problem = "Could not decrypt the record";
            }
            Bundle bundle = null;
            if (problem == null) {
                String checksum = Json.text(entry, "checksum");
                if (checksum != null && !checksum.equalsIgnoreCase(md5(document)) && !checksum.equalsIgnoreCase(sha256(document))) {
                    problem = "Checksum does not match the record";
                } else {
                    try {
                        bundle = fhir.decode(document);
                    } catch (RuntimeException e) {
                        problem = "Not a FHIR R4 bundle";
                    }
                }
            }
            if (problem == null && bundle.getEntry().isEmpty()) {
                problem = "Empty bundle";
            }
            if (problem != null) {
                failures.put(reference, problem);
                continue;
            }
            Composition composition = bundle.getEntryFirstRep().getResource() instanceof Composition c ? c : null;
            String title = composition != null && composition.hasTitle() ? composition.getTitle() : "Health record";
            String type = composition == null ? bundle.getType() == null ? null : bundle.getType().toCode()
                    : composition.hasMeta() && composition.getMeta().hasProfile()
                    ? profileName(composition.getMeta().getProfile().get(0).getValue())
                    : composition.getType().getText();
            Instant date = composition != null && composition.hasDate() ? composition.getDate().toInstant()
                    : bundle.hasTimestamp() ? bundle.getTimestamp().toInstant() : null;
            if (repository.insertRecord(new HiuRepository.NewRecord(request.id(), request.consentId(),
                    artefact.patientId(), reference, page, type, title, date, document, sha256(document),
                    artefact.eraseAt()))) {
                stored++;
            }
        }
        if (!failures.isEmpty()) {
            try {
                repository.addFailures(request.id(), json.writeValueAsString(failures));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        repository.pageReceived(request.id(), pageCount);
        if (stored > 0) {
            audit.record(Action.CREATE, "hiu_health_record", request.id(), artefact.patientId(),
                    Map.of("hip", String.valueOf(artefact.hipId()), "records", stored, "page", page));
        }
        DataRequest updated = repository.dataRequest(request.id()).orElseThrow();
        if (updated.pagesReceived() >= pageCount) {
            complete(updated, artefact);
        }
    }

    private void complete(DataRequest request, Artefact artefact) {
        keys.forget(request.id());
        List<String> received = repository.receivedReferences(request.id());
        Map<String, String> failures = failures(request.id());
        String status = received.isEmpty() ? "FAILED" : failures.isEmpty() ? "TRANSFERRED" : "PARTIAL";
        repository.finishDataRequest(request.id(), status,
                failures.isEmpty() ? null : failures.size() + " record(s) could not be accepted", time.now());
        List<Map<String, String>> statuses = new ArrayList<>();
        received.forEach(reference -> statuses.add(Map.of("careContextReference", reference, "hiStatus", "OK",
                "description", "Received")));
        failures.forEach((reference, problem) -> statuses.add(Map.of("careContextReference", reference,
                "hiStatus", "ERRORED", "description", problem)));
        Map<String, Object> statusNotification = new LinkedHashMap<>();
        statusNotification.put("sessionStatus", received.isEmpty() ? "FAILED" : "TRANSFERRED");
        statusNotification.put("hipId", artefact.hipId());
        statusNotification.put("statusResponses", statuses);
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("consentId", artefact.consentId().toString());
        notification.put("transactionId", request.transactionId().toString());
        notification.put("doneAt", ABDM_TIME.format(time.now()));
        notification.put("notifier", Map.of("type", "HIU", "id", properties.hipId()));
        notification.put("statusNotification", statusNotification);
        UUID transactionId = request.transactionId();
        tasks.afterCommit("hiu-health-information-notify", () -> {
            try {
                abdm.gatewayPost(AbdmPaths.HEALTH_INFORMATION_NOTIFY, Map.of("notification", notification),
                        abdm.asHiu(Call.plain().withTransaction(transactionId)));
            } catch (AbdmCallException e) {
                log.warn("Transfer notification for {} failed: {}", transactionId, e.code());
            }
        });
    }

    private Map<String, String> failures(UUID dataRequestId) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            json.readTree(repository.failures(dataRequestId)).fields()
                    .forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            return map;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ reading (doctor desk)

    public record TransferView(UUID id, String status, Instant requestedAt, Instant completedAt, int records,
                               String error) {
    }

    public record ArtefactView(UUID consentId, String status, String hipId, String hipName, List<String> hiTypes,
                               Instant from, Instant to, Instant eraseAt, boolean detailsReceived,
                               List<TransferView> transfers) {
    }

    public record ConsentView(UUID id, String status, String purpose, List<String> hiTypes, Instant from, Instant to,
                              Instant eraseAt, String requestedBy, Instant requestedAt, String error,
                              List<ArtefactView> artefacts) {
    }

    public record RecordView(UUID id, String hipId, String hipName, String recordType, String title, Instant recordDate,
                             Instant receivedAt, Instant eraseAt) {
    }

    public record RecordDetail(RecordView record, RecordSummary.Summary summary) {
    }

    @Transactional(readOnly = true)
    public List<ConsentView> consents(String uhid) {
        PatientDirectory.Patient patient = patient(uhid);
        List<ConsentRequest> requests = repository.requestsForPatient(patient.id());
        List<Artefact> artefacts = repository.artefactsForRequests(requests.stream().map(ConsentRequest::id).toList());
        List<DataRequest> transfers = repository.dataRequests(artefacts.stream().map(Artefact::consentId).toList());
        return requests.stream().map(r -> new ConsentView(r.id(), r.status(), r.purpose(), r.hiTypes(), r.from(),
                r.to(), r.eraseAt(), r.requestedByName(), r.requestedAt(), r.error(),
                artefacts.stream().filter(a -> a.requestRowId().equals(r.id())).map(a -> new ArtefactView(
                        a.consentId(), a.status(), a.hipId(), a.hipName(), a.hiTypes(), a.from(), a.to(), a.eraseAt(),
                        a.fetchedAt() != null,
                        transfers.stream().filter(t -> t.consentId().equals(a.consentId())).map(this::transfer).toList()))
                        .toList())).toList();
    }

    @Transactional(readOnly = true)
    public List<RecordView> records(String uhid) {
        return repository.recordsForPatient(patient(uhid).id(), time.now()).stream().map(HiuService::view).toList();
    }

    @Transactional
    public RecordDetail record(UUID id) {
        HiuRepository.StoredRecord record = repository.record(id, time.now())
                .orElseThrow(() -> ApiException.notFound("Record", id));
        Bundle bundle = fhir.decode(repository.bundle(id));
        audit.record(Action.READ, "hiu_health_record", id, record.patientId(),
                Map.of("hip", String.valueOf(record.hipId()), "type", String.valueOf(record.recordType())));
        return new RecordDetail(view(record), RecordSummary.of(bundle, time.zone().getId()));
    }

    /** The document exactly as received (FHIR JSON), for download. */
    @Transactional
    public String bundle(UUID id) {
        HiuRepository.StoredRecord record = repository.record(id, time.now())
                .orElseThrow(() -> ApiException.notFound("Record", id));
        audit.record(Action.EXPORT, "hiu_health_record", id, record.patientId(),
                Map.of("hip", String.valueOf(record.hipId())));
        return repository.bundle(id);
    }

    public UUID patientId(String uhid) {
        return patient(uhid).id();
    }

    private TransferView transfer(DataRequest t) {
        return new TransferView(t.id(), t.status(), t.requestedAt(), t.completedAt(), repository.recordCount(t.id()),
                t.error());
    }

    private static RecordView view(HiuRepository.StoredRecord r) {
        return new RecordView(r.id(), r.hipId(), r.hipName(), r.recordType(), r.title(), r.recordDate(), r.receivedAt(),
                r.eraseAt());
    }

    private PatientDirectory.Patient patient(String uhid) {
        return patients.byUhid(uhid).orElseThrow(() -> ApiException.notFound("Patient", uhid));
    }

    // ------------------------------------------------------------------ housekeeping

    /** Erases records past their consent's erase time, and fails transfers whose key has expired. */
    @Scheduled(fixedDelay = 60, initialDelay = 45, timeUnit = TimeUnit.SECONDS)
    @Transactional
    public void sweep() {
        Instant now = time.now();
        int erased = repository.eraseExpired(now);
        if (erased > 0) {
            log.info("Erased {} health record(s) received under consents past their erase time", erased);
        }
        for (UUID id : repository.staleDataRequests(now.minus(properties.hiu().keyValidity()))) {
            keys.forget(id);
            repository.finishDataRequest(id, "FAILED", "No records arrived before the decryption key expired", now);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void acknowledge(String path, UUID inboundRequestId, Map<String, Object> body) {
        Map<String, Object> reply = new LinkedHashMap<>(body);
        reply.put("response", Map.of("requestId", inboundRequestId.toString()));
        try {
            abdm.gatewayPost(path, reply, abdm.asHiu(Call.plain()));
            messages.inboundOutcome(inboundRequestId, Outcome.ACKED, null, null);
        } catch (AbdmCallException e) {
            messages.inboundOutcome(inboundRequestId, Outcome.ERROR, e.abdmCode() != null ? e.abdmCode() : e.code(),
                    e.getMessage());
        }
    }

    private Requester requester(String subject) {
        Optional<Requester> found = jdbc.sql("""
                SELECT id, concat_ws(' ', title, full_name) AS name, council_registration
                FROM core.staff WHERE iam_subject = :s AND is_active AND staff_role IN ('DOCTOR', 'RADIOLOGIST')
                """).param("s", subject).query((rs, n) -> new Requester(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("council_registration"))).optional();
        Requester requester = found.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_A_DOCTOR",
                "Only a registered doctor can ask for a patient's records from other hospitals"));
        if (requester.registration() == null || requester.registration().isBlank()) {
            throw ApiException.unprocessable("REGISTRATION_REQUIRED",
                    "Your medical council registration is needed to ask for consent");
        }
        return requester;
    }

    private static String errorMessage(JsonNode body) {
        JsonNode error = body.path("error");
        if (error.isMissingNode() || error.isNull()) {
            return null;
        }
        String message = Json.text(error, "message");
        return "ABDM: " + (message == null ? "error" : Redact.text(message));
    }

    /** {@code https://nrces.in/.../StructureDefinition/OPConsultRecord} → {@code OPConsultRecord}. */
    private static String profileName(String profile) {
        return profile == null ? null : profile.substring(profile.lastIndexOf('/') + 1);
    }

    static String sha256(String value) {
        return digest("SHA-256", value);
    }

    private static String md5(String value) {
        return digest("MD5", value);
    }

    private static String digest(String algorithm, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
