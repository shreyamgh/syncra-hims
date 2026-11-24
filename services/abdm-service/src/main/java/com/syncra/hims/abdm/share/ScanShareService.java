package com.syncra.hims.abdm.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.abha.AbhaLinker;
import com.syncra.hims.abdm.abha.AbhaLinker.LinkedAbha;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.abha.VerificationRepository;
import com.syncra.hims.abdm.callback.CallbackAck;
import com.syncra.hims.abdm.callback.Json;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.abdm.share.PatientShareRepository.Share;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Milestone 1, Scan &amp; Share: the patient scans the counter's QR code in their ABHA app and
 * ABDM delivers their verified profile here ({@code /hip/patient/share}). The patient gets a
 * queue number back in the app ({@code on-share}); the desk sees the profile, registers or
 * finds the patient, and the ABHA is attached as verified (method QR_SCAN).
 */
@Service
public class ScanShareService {

    private static final String DEFAULT_CONTEXT = "DEFAULT";

    private final PatientShareRepository shares;
    private final PatientDirectory patients;
    private final VerificationRepository verifications;
    private final AbhaLinker linker;
    private final CallbackAck acks;
    private final AbdmTasks tasks;
    private final AbdmProperties properties;
    private final CurrentUser currentUser;
    private final HospitalTime time;

    public ScanShareService(PatientShareRepository shares, PatientDirectory patients,
                            VerificationRepository verifications, AbhaLinker linker, CallbackAck acks, AbdmTasks tasks,
                            AbdmProperties properties, CurrentUser currentUser, HospitalTime time) {
        this.shares = shares;
        this.patients = patients;
        this.verifications = verifications;
        this.linker = linker;
        this.acks = acks;
        this.tasks = tasks;
        this.properties = properties;
        this.currentUser = currentUser;
        this.time = time;
    }

    // ------------------------------------------------------------------ ABDM callback

    @Transactional
    public void onShare(JsonNode body, UUID requestId) {
        JsonNode profile = body.path("profile").path("patient");
        String context = Json.text(body, "metaData.context");
        String hipId = Json.text(body, "metaData.hipId");
        String address = AbhaNumbers.address(Json.text(profile, "abhaAddress"));
        String name = Json.text(profile, "name");
        if (hipId != null && properties.hipId() != null && !hipId.equals(properties.hipId())) {
            tasks.afterCommit("on-share", () -> reject(requestId, address, "Profile shared with another HIP"));
            return;
        }
        if (address == null || name == null) {
            tasks.afterCommit("on-share", () -> reject(requestId, address, "Profile without ABHA address or name"));
            return;
        }
        String counter = context == null ? DEFAULT_CONTEXT : context.length() > 64 ? context.substring(0, 64) : context;
        Instant now = time.now();
        LocalDate today = time.today();

        Share share = shares.byGatewayRequest(requestId)
                .or(() -> shares.livePending(today, counter, address, now))
                .orElseGet(() -> {
                    String number = AbhaNumbers.digits(Json.text(profile, "abhaNumber"));
                    UUID matched = patients.byAbha(number, address).map(PatientDirectory.Patient::id).orElse(null);
                    return shares.insert(new PatientShareRepository.NewShare(requestId, today, counter, number, address,
                            name.length() > 200 ? name.substring(0, 200) : name,
                            gender(Json.text(profile, "gender")), small(profile, "yearOfBirth", 1900, 2100),
                            small(profile, "monthOfBirth", 1, 12), small(profile, "dayOfBirth", 1, 31),
                            mobile(profile), truncate(Json.text(profile, "address.line"), 250),
                            truncate(Json.text(profile, "address.district"), 100),
                            truncate(Json.text(profile, "address.state"), 60), pincode(Json.text(profile, "address.pincode")),
                            matched, now.plus(properties.scanShare().tokenValidity())));
                });
        Share issued = share;
        tasks.afterCommit("on-share", () -> acknowledge(requestId, issued));
    }

    private void acknowledge(UUID requestId, Share share) {
        long expirySeconds = Math.max(0, share.expiresAt().getEpochSecond() - time.now().getEpochSecond());
        Map<String, Object> acknowledgement = new LinkedHashMap<>();
        acknowledgement.put("abhaAddress", share.abhaAddress());
        acknowledgement.put("status", "SUCCESS");
        acknowledgement.put("profile", Map.of("context", share.context(), "tokenNumber",
                String.valueOf(share.tokenNumber()), "expiry", String.valueOf(expirySeconds)));
        acks.send(AbdmPaths.PATIENT_SHARE_ON_SHARE, requestId, Map.of("acknowledgement", acknowledgement), null);
    }

    private void reject(UUID requestId, String address, String reason) {
        Map<String, Object> acknowledgement = new LinkedHashMap<>();
        if (address != null) {
            acknowledgement.put("abhaAddress", address);
        }
        acknowledgement.put("status", "FAILURE");
        acks.send(AbdmPaths.PATIENT_SHARE_ON_SHARE, requestId, Map.of("acknowledgement", acknowledgement,
                "error", CallbackAck.error(CallbackAck.INVALID_REQUEST, reason)), null);
    }

    // ------------------------------------------------------------------ desk

    public List<Share> pending(String context) {
        return shares.pending(time.today(), context == null || context.isBlank() ? null : context, time.now());
    }

    /** The desk registered (or found) the patient: attach the shared ABHA as verified. */
    @Transactional
    public LinkedAbha register(UUID shareId, UUID patientId) {
        Share share = shares.lock(shareId).orElseThrow(() -> ApiException.notFound("Scan & Share entry", shareId));
        if (!"PENDING".equals(share.status()) || !share.expiresAt().isAfter(time.now())) {
            throw ApiException.conflict("SHARE_CLOSED", "Token " + share.tokenNumber() + " is "
                    + ("PENDING".equals(share.status()) ? "expired" : share.status().toLowerCase()));
        }
        String actor = currentUser.subject();
        UUID verificationId = verifications.success(VerificationRepository.Method.QR_SCAN, share.abhaNumber(),
                share.abhaAddress(), share.gender(), share.yearOfBirth(), share.gatewayRequestId().toString(), actor);
        LinkedAbha linked = linker.link(patientId, verificationId);
        shares.markRegistered(shareId, patientId, actor, time.now());
        return linked;
    }

    @Scheduled(fixedDelay = 15, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purge() {
        Instant now = time.now();
        shares.purge(now, now.minus(properties.scanShare().retention()));
    }

    // ------------------------------------------------------------------ parsing

    private static String gender(String value) {
        if (value == null) {
            return "U";
        }
        return switch (value.trim().toUpperCase()) {
            case "M", "MALE" -> "M";
            case "F", "FEMALE" -> "F";
            case "O", "OTHER", "T" -> "O";
            default -> "U";
        };
    }

    private static Integer small(JsonNode node, String field, int min, int max) {
        String value = Json.text(node, field);
        if (value == null || !value.matches("\\d{1,4}")) {
            return null;
        }
        int number = Integer.parseInt(value);
        return number < min || number > max ? null : number;
    }

    private static String mobile(JsonNode profile) {
        String value = Json.text(profile, "phoneNumber");
        if (value == null) {
            value = Json.text(profile, "mobile");
        }
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        digits = digits.length() == 12 && digits.startsWith("91") ? digits.substring(2) : digits;
        return digits.matches("^[6-9][0-9]{9}$") ? digits : null;
    }

    private static String pincode(String value) {
        return value != null && value.matches("^[1-9][0-9]{5}$") ? value : null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
