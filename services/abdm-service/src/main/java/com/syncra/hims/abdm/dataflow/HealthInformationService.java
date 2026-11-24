package com.syncra.hims.abdm.dataflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.callback.CallbackAck;
import com.syncra.hims.abdm.callback.Json;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.consent.ConsentRepository;
import com.syncra.hims.abdm.consent.ConsentRepository.Consent;
import com.syncra.hims.abdm.crypto.Fidelius;
import com.syncra.hims.abdm.dataflow.HealthInformationRepository.HiRequest;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.common.config.HospitalTime;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health-information requests from an HIU, relayed by ABDM ({@code /hip/health-information/request}).
 * The request is checked against its consent artefact (status, expiry, date range, key
 * material, push URL), stored, and handed to {@link DataTransferWorker}, which acknowledges it
 * and transfers the records.
 */
@Service
public class HealthInformationService {

    private static final int MAX_URL_LENGTH = 500;

    private final HealthInformationRepository requests;
    private final ConsentRepository consents;
    private final DataTransferWorker worker;
    private final CallbackAck acks;
    private final AbdmTasks tasks;
    private final AbdmProperties properties;
    private final HospitalTime time;

    public HealthInformationService(HealthInformationRepository requests, ConsentRepository consents,
                                    DataTransferWorker worker, CallbackAck acks, AbdmTasks tasks,
                                    AbdmProperties properties, HospitalTime time) {
        this.requests = requests;
        this.consents = consents;
        this.worker = worker;
        this.acks = acks;
        this.tasks = tasks;
        this.properties = properties;
        this.time = time;
    }

    @Transactional
    public void onRequest(JsonNode body, UUID requestId) {
        UUID transactionId = Json.uuid(body, "transactionId");
        UUID consentId = Json.uuid(body, "hiRequest.consent.id");
        Consent consent = consentId == null ? null : consents.byArtefactId(consentId).orElse(null);
        if (transactionId == null || consent == null) {
            String reason = transactionId == null ? "Missing transactionId" : "Unknown consent artefact";
            tasks.afterCommit("hi-on-request", () -> errorAck(requestId, transactionId, reason));
            return;
        }

        JsonNode hi = body.path("hiRequest");
        Instant from = Json.instant(hi, "dateRange.from");
        Instant to = Json.instant(hi, "dateRange.to");
        String pushUrl = Json.text(hi, "dataPushUrl");
        String publicKey = Json.text(hi, "keyMaterial.dhPublicKey.keyValue");
        String nonce = Json.text(hi, "keyMaterial.nonce");
        Instant keyExpiry = Json.instant(hi, "keyMaterial.dhPublicKey.expiry");
        String rejection = rejection(consent, from, to, pushUrl, Json.text(hi, "keyMaterial.cryptoAlg"),
                Json.text(hi, "keyMaterial.curve"), publicKey, nonce, keyExpiry);

        HiRequest stored = requests.insertIfNew(new HealthInformationRepository.NewRequest(transactionId, requestId,
                consent.id(),
                from != null && to != null && !to.isBefore(from) ? from : consent.dateFrom(),
                from != null && to != null && !to.isBefore(from) ? to : consent.dateTo(),
                pushUrl == null || pushUrl.length() > MAX_URL_LENGTH ? "" : pushUrl,
                Objects.requireNonNullElse(publicKey, ""),
                nonce == null || nonce.length() > 64 ? "" : nonce, keyExpiry,
                rejection == null ? "REQUESTED" : "REJECTED", rejection));
        tasks.afterCommit("hi-transfer", () -> worker.process(stored.id()));
    }

    private String rejection(Consent consent, Instant from, Instant to, String pushUrl, String cryptoAlg, String curve,
                             String publicKey, String nonce, Instant keyExpiry) {
        Instant now = time.now();
        if (!consent.isUsable(now)) {
            return "Consent is " + ("GRANTED".equals(consent.status()) ? "past its erase-by date" : consent.status().toLowerCase());
        }
        if (from == null || to == null || to.isBefore(from)) {
            return "Invalid dateRange";
        }
        if (from.isBefore(consent.dateFrom()) || to.isAfter(consent.dateTo())) {
            return "Requested dateRange is outside the consented period";
        }
        if (!Fidelius.ALGORITHM.equalsIgnoreCase(cryptoAlg) || !Fidelius.CURVE.equalsIgnoreCase(curve)) {
            return "Unsupported key material (ECDH on Curve25519 is required)";
        }
        if (!Fidelius.isUsablePublicKey(publicKey) || !Fidelius.isUsableNonce(nonce)) {
            return "Invalid dhPublicKey or nonce";
        }
        if (keyExpiry != null && !keyExpiry.isAfter(now)) {
            return "dhPublicKey has expired";
        }
        return pushUrlProblem(pushUrl);
    }

    /** Push URLs come from the HIU: plain HTTP and internal addresses are refused unless explicitly allowed. */
    String pushUrlProblem(String pushUrl) {
        if (pushUrl == null || pushUrl.length() > MAX_URL_LENGTH) {
            return "Missing or overlong dataPushUrl";
        }
        URI uri;
        try {
            uri = URI.create(pushUrl);
        } catch (IllegalArgumentException e) {
            return "Malformed dataPushUrl";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            return "Malformed dataPushUrl";
        }
        if (!properties.dataPush().requireHttps()) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "dataPushUrl must use HTTPS";
        }
        String host = uri.getHost().toLowerCase();
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".internal")) {
            return "dataPushUrl points at an internal host";
        }
        if (host.matches("[0-9.]+") || host.contains(":")) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()) {
                    return "dataPushUrl points at an internal address";
                }
            } catch (java.net.UnknownHostException e) {
                return "Malformed dataPushUrl";
            }
        }
        return null;
    }

    private void errorAck(UUID requestId, UUID transactionId, String reason) {
        Map<String, Object> hiRequest = new LinkedHashMap<>();
        if (transactionId != null) {
            hiRequest.put("transactionId", transactionId.toString());
        }
        hiRequest.put("sessionStatus", "ERRORED");
        int code = transactionId == null ? CallbackAck.INVALID_REQUEST : CallbackAck.NOT_FOUND;
        acks.send(AbdmPaths.HEALTH_INFORMATION_ON_REQUEST, requestId,
                Map.of("hiRequest", hiRequest, "error", CallbackAck.error(code, reason)), transactionId);
    }
}
