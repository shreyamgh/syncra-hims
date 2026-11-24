package com.syncra.hims.abdm.dataflow;

import com.syncra.hims.abdm.callback.CallbackAck;
import com.syncra.hims.abdm.carecontext.CareContextRepository;
import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.consent.ConsentRepository;
import com.syncra.hims.abdm.consent.ConsentRepository.Consent;
import com.syncra.hims.abdm.crypto.Fidelius;
import com.syncra.hims.abdm.crypto.Fidelius.KeyMaterial;
import com.syncra.hims.abdm.dataflow.HealthInformationRepository.HiRequest;
import com.syncra.hims.abdm.dataflow.HealthInformationRepository.Transfer;
import com.syncra.hims.abdm.fhir.EncounterRecord;
import com.syncra.hims.abdm.fhir.HealthRecords;
import com.syncra.hims.abdm.fhir.HealthRecords.Document;
import com.syncra.hims.abdm.fhir.HiType;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.GatewayMessageLog;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carries out a health-information request:
 *
 * <ol>
 *   <li>{@code on-request}: ACKNOWLEDGED, or ERRORED with the reason it was rejected.</li>
 *   <li>For each consented care context whose encounter falls in the requested period, and each
 *       consented record type it has, the FHIR document is generated, Fidelius-encrypted with a
 *       one-time key pair for this transfer, and pushed to the HIU's {@code dataPushUrl} in pages.</li>
 *   <li>{@code health-information/notify}: TRANSFERRED or FAILED, with a status per care context.</li>
 * </ol>
 * Only digests and sizes of what was sent are stored. A scheduled sweep finishes requests that a
 * restart interrupted and reports stalled transfers as failed.
 */
@Component
public class DataTransferWorker {

    private static final Logger log = LoggerFactory.getLogger(DataTransferWorker.class);
    private static final Duration SWEEP_AFTER = Duration.ofMinutes(1);
    private static final Duration STALLED_AFTER = Duration.ofMinutes(15);

    private final HealthInformationRepository requests;
    private final ConsentRepository consents;
    private final CareContextRepository careContexts;
    private final HealthRecords records;
    private final AbdmClient abdm;
    private final CallbackAck acks;
    private final GatewayMessageLog messages;
    private final AuditService audit;
    private final AbdmProperties properties;
    private final HospitalTime time;
    private final TransactionTemplate transaction;

    public DataTransferWorker(HealthInformationRepository requests, ConsentRepository consents,
                              CareContextRepository careContexts, HealthRecords records, AbdmClient abdm,
                              CallbackAck acks, GatewayMessageLog messages, AuditService audit,
                              AbdmProperties properties, HospitalTime time, PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.consents = consents;
        this.careContexts = careContexts;
        this.records = records;
        this.abdm = abdm;
        this.acks = acks;
        this.messages = messages;
        this.audit = audit;
        this.properties = properties;
        this.time = time;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void process(UUID requestRowId) {
        HiRequest request = requests.find(requestRowId).orElse(null);
        if (request == null) {
            return;
        }
        switch (request.status()) {
            case "REJECTED" -> {
                if (!messages.isAnswered(request.gatewayRequestId())) {
                    acknowledge(request, request.rejectionReason());
                }
            }
            case "REQUESTED" -> {
                boolean answered = messages.isAnswered(request.gatewayRequestId()) || acknowledge(request, null);
                if (answered && requests.transition(request.id(), "REQUESTED", "ACKNOWLEDGED")) {
                    transfer(request);
                }
            }
            case "ACKNOWLEDGED" -> transfer(request);
            default -> { }
        }
    }

    private boolean acknowledge(HiRequest request, String rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hiRequest", Map.of("transactionId", request.transactionId().toString(),
                "sessionStatus", rejection == null ? "ACKNOWLEDGED" : "ERRORED"));
        if (rejection != null) {
            body.put("error", CallbackAck.error(CallbackAck.INVALID_REQUEST, rejection));
        }
        return acks.send(AbdmPaths.HEALTH_INFORMATION_ON_REQUEST, request.gatewayRequestId(), body,
                request.transactionId());
    }

    // ------------------------------------------------------------------ transfer

    private record Entry(CareContext careContext, Document document, String encrypted) {
    }

    private void transfer(HiRequest request) {
        if (!requests.transition(request.id(), "ACKNOWLEDGED", "TRANSFERRING")) {
            return; // another worker has it
        }
        Consent consent = consents.find(request.consentRowId()).orElseThrow();
        Map<String, String> outcome = new LinkedHashMap<>(); // careContextReference -> problem (null = delivered)
        List<Transfer> transfers = new ArrayList<>();
        if (!consent.isUsable(time.now())) {
            finish(request, consent, "FAILED", transfers, outcome, "Consent was revoked or expired before transfer");
            return;
        }

        KeyMaterial key = Fidelius.generateKeyMaterial();
        List<Entry> entries = new ArrayList<>();
        Set<String> sentTypes = new LinkedHashSet<>();
        for (CareContext context : careContexts.forConsent(consent.id())) {
            Instant started = context.encounterStartedAt();
            if (started.isBefore(request.dateFrom()) || started.isAfter(request.dateTo())) {
                continue;
            }
            Optional<EncounterRecord> record = records.record(context.encounterId());
            List<Document> documents = new ArrayList<>();
            if (record.isPresent()) {
                Set<HiType> available = records.available(record.get());
                for (String code : consent.hiTypes()) {
                    HiType.fromCode(code).filter(available::contains).filter(t -> context.hiTypes().contains(code))
                            .flatMap(t -> records.document(record.get(), t)).ifPresent(documents::add);
                }
            }
            if (documents.isEmpty()) {
                outcome.put(context.referenceNumber(), "No records of the consented types in this care context");
                continue;
            }
            for (Document document : documents) {
                String encrypted = Fidelius.encrypt(document.json(), key.nonce(), request.hiuNonce(), key.privateKey(),
                        request.hiuPublicKey());
                entries.add(new Entry(context, document, encrypted));
                sentTypes.add(document.type().code());
            }
            outcome.putIfAbsent(context.referenceNumber(), null);
        }

        boolean allDelivered = push(request, key, entries, outcome, transfers);
        finish(request, consent, allDelivered ? "TRANSFERRED" : "FAILED", transfers, outcome, null);
        log.info("Health information transfer {}: {} record(s), {}", request.transactionId(), entries.size(),
                allDelivered ? "delivered" : "not all delivered");
        if (!entries.isEmpty()) {
            audit.record(Action.SHARE, "health_information_request", request.transactionId(), consent.patientId(),
                    Map.of("hiu", String.valueOf(consent.hiuId()), "records", entries.size(), "hiTypes", sentTypes,
                            "delivered", allDelivered));
        }
    }

    /** Pushes the entries page by page; stops at the first page the HIU does not accept. */
    private boolean push(HiRequest request, KeyMaterial key, List<Entry> entries, Map<String, String> outcome,
                         List<Transfer> transfers) {
        if (entries.isEmpty()) {
            return true;
        }
        int perPage = properties.dataPush().entriesPerPage();
        int pageCount = (entries.size() + perPage - 1) / perPage;
        Map<String, Object> keyMaterial = Map.of(
                "cryptoAlg", Fidelius.ALGORITHM,
                "curve", Fidelius.CURVE,
                "dhPublicKey", Map.of(
                        "expiry", DateTimeFormatter.ISO_INSTANT.format(time.now()
                                .plus(properties.dataPush().keyValidity()).truncatedTo(ChronoUnit.MILLIS)),
                        "parameters", Fidelius.KEY_PARAMETERS,
                        "keyValue", key.x509PublicKey()),
                "nonce", key.nonce());
        String failure = null;
        for (int page = 0; page < pageCount; page++) {
            List<Entry> slice = entries.subList(page * perPage, Math.min(entries.size(), (page + 1) * perPage));
            if (failure == null) {
                List<Map<String, String>> content = slice.stream().map(e -> Map.of(
                        "content", e.encrypted(),
                        "media", "application/fhir+json",
                        "checksum", e.document().md5(),
                        "careContextReference", e.careContext().referenceNumber())).toList();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("pageNumber", page + 1);
                body.put("pageCount", pageCount);
                body.put("transactionId", request.transactionId().toString());
                body.put("entries", content);
                body.put("keyMaterial", keyMaterial);
                try {
                    abdm.pushToHiu(URI.create(request.dataPushUrl()), body, Call.plain().withTransaction(request.transactionId()));
                } catch (AbdmCallException e) {
                    failure = "HIU did not accept the data (" + e.code() + ")";
                }
            }
            Instant sentAt = time.now();
            for (Entry e : slice) {
                transfers.add(new Transfer(e.careContext().id(), e.document().type().code(), e.document().sha256(),
                        e.document().sizeBytes(), failure == null ? "DELIVERED" : "ERRORED", failure));
                if (failure != null) {
                    outcome.put(e.careContext().referenceNumber(), failure);
                }
            }
            if (failure != null) {
                log.warn("Health information transfer {} stopped at page {} of {}", request.transactionId(), page + 1,
                        pageCount);
            }
            final Instant at = sentAt;
            transaction.executeWithoutResult(s -> transfers.subList(transfers.size() - slice.size(), transfers.size())
                    .forEach(t -> requests.recordTransfer(request.id(), t, at)));
        }
        return failure == null;
    }

    private void finish(HiRequest request, Consent consent, String status, List<Transfer> transfers,
                        Map<String, String> outcome, String reason) {
        requests.transition(request.id(), "TRANSFERRING", status);
        List<Map<String, String>> statuses = new ArrayList<>();
        outcome.forEach((reference, problem) -> statuses.add(Map.of(
                "careContextReference", reference,
                "hiStatus", problem == null ? "DELIVERED" : "ERRORED",
                "description", problem == null ? "Delivered" : problem)));
        String hipId = properties.hipIdOrPlaceholder();
        Map<String, Object> statusNotification = new LinkedHashMap<>();
        statusNotification.put("sessionStatus", status);
        statusNotification.put("hipId", hipId);
        statusNotification.put("statusResponses", statuses);
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("consentId", consent.artefactId().toString());
        notification.put("transactionId", request.transactionId().toString());
        notification.put("doneAt", DateTimeFormatter.ISO_INSTANT.format(time.now().truncatedTo(ChronoUnit.MILLIS)));
        notification.put("notifier", Map.of("type", "HIP", "id", hipId));
        notification.put("statusNotification", statusNotification);
        try {
            abdm.gatewayPost(AbdmPaths.HEALTH_INFORMATION_NOTIFY, Map.of("notification", notification),
                    abdm.asHip(Call.plain().withTransaction(request.transactionId())));
        } catch (AbdmCallException e) {
            log.warn("Transfer notification for {} failed: {}", request.transactionId(), e.code());
        }
        if (reason != null) {
            log.info("Health information transfer {} {}: {}", request.transactionId(), status.toLowerCase(), reason);
        }
    }

    // ------------------------------------------------------------------ sweeps

    @Scheduled(fixedDelay = 60, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = time.now();
        consents.expire(now);
        for (UUID id : requests.stalled(now.minus(STALLED_AFTER))) {
            requests.find(id).ifPresent(r -> consents.find(r.consentRowId()).ifPresent(c ->
                    finish(r, c, "FAILED", List.of(), new LinkedHashMap<>(), "transfer interrupted")));
        }
        for (UUID id : requests.due(now.minus(SWEEP_AFTER), 20)) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.warn("Health information request sweep failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
