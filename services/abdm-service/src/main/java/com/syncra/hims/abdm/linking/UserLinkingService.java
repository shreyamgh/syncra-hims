package com.syncra.hims.abdm.linking;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.callback.CallbackAck;
import com.syncra.hims.abdm.callback.Json;
import com.syncra.hims.abdm.carecontext.CareContextGroups;
import com.syncra.hims.abdm.carecontext.CareContextRepository;
import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import com.syncra.hims.abdm.carecontext.CareContextService;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.fhir.EncounterRecord;
import com.syncra.hims.abdm.fhir.HealthRecords;
import com.syncra.hims.abdm.fhir.HiType;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.abdm.gateway.AbdmTasks;
import com.syncra.hims.abdm.linking.LinkingRepository.Discovery;
import com.syncra.hims.abdm.linking.LinkingRepository.Session;
import com.syncra.hims.abdm.linking.PatientMatcher.Matched;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Milestone 2, patient-initiated linking: the patient finds this hospital in their PHR app and
 * links their records themselves.
 *
 * <pre>
 *  discover  ──► match one patient ({@link PatientMatcher}); offer unlinked care contexts ──► on-discover
 *  init      ──► care contexts from that discovery only; OTP by SMS to the registered mobile ──► on-init
 *  confirm   ──► OTP checked (limited attempts, expiry) ──► care contexts LINKED ──► on-confirm
 * </pre>
 * Every step answers ABDM asynchronously after its data is committed. The OTP exists only in
 * memory and in the SMS; the database keeps a salted hash.
 */
@Service
public class UserLinkingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ABHA_NUMBER_TYPES = Set.of("ABHA_NUMBER", "NDHM_HEALTH_NUMBER", "HEALTH_NUMBER");

    private final LinkingRepository linking;
    private final CareContextRepository careContexts;
    private final CareContextService careContextService;
    private final HealthRecords records;
    private final PatientDirectory patients;
    private final OtpSender otp;
    private final CallbackAck acks;
    private final AbdmTasks tasks;
    private final AuditService audit;
    private final AbdmProperties properties;
    private final HospitalTime time;

    public UserLinkingService(LinkingRepository linking, CareContextRepository careContexts,
                              CareContextService careContextService, HealthRecords records, PatientDirectory patients,
                              OtpSender otp, CallbackAck acks, AbdmTasks tasks, AuditService audit,
                              AbdmProperties properties, HospitalTime time) {
        this.linking = linking;
        this.careContexts = careContexts;
        this.careContextService = careContextService;
        this.records = records;
        this.patients = patients;
        this.otp = otp;
        this.acks = acks;
        this.tasks = tasks;
        this.audit = audit;
        this.properties = properties;
        this.time = time;
    }

    // ------------------------------------------------------------------ discover

    @Transactional
    public void onDiscover(JsonNode body, UUID requestId) {
        UUID transactionId = Json.uuid(body, "transactionId");
        JsonNode patient = body.path("patient");
        String address = AbhaNumbers.address(Json.text(patient, "id"));
        if (transactionId == null || address == null) {
            tasks.afterCommit("on-discover", () -> discoverError(requestId, transactionId, CallbackAck.INVALID_REQUEST,
                    "transactionId and patient.id (ABHA address) are required"));
            return;
        }
        Optional<Discovery> earlier = linking.discovery(transactionId);
        Discovery discovery = earlier.orElseGet(() -> discover(transactionId, requestId, address, patient));
        tasks.afterCommit("on-discover", () -> answerDiscovery(requestId, discovery));
    }

    private Discovery discover(UUID transactionId, UUID requestId, String address, JsonNode patient) {
        PatientMatcher.Query query = query(address, patient);
        PatientMatcher.Result result = PatientMatcher.match(query, linking.candidates(query));
        UUID patientId = null;
        List<UUID> offered = List.of();
        List<String> matchedBy = List.of();
        String outcome = switch (result) {
            case PatientMatcher.NotFound n -> "NOT_FOUND";
            case PatientMatcher.Ambiguous a -> "AMBIGUOUS";
            case Matched m -> {
                offered = offer(m.patient().patientId(), address);
                matchedBy = m.matchedBy();
                if (offered.isEmpty()) {
                    yield "NO_RECORDS";
                }
                patientId = m.patient().patientId();
                yield "MATCHED";
            }
        };
        Instant expires = time.now().plus(properties.discovery().validity());
        linking.recordDiscovery(transactionId, requestId, address, patientId, offered, matchedBy, outcome, expires);
        if (patientId != null) {
            audit.record(Action.READ, "discovery_request", transactionId, patientId,
                    Map.of("matchedBy", matchedBy, "careContexts", offered.size()));
        }
        return linking.discovery(transactionId).orElseThrow();
    }

    /** Care contexts for the patient's recent encounters with shareable records, minus those already linked. */
    private List<UUID> offer(UUID patientId, String address) {
        List<UUID> offered = new ArrayList<>();
        for (UUID encounterId : linking.recentEncounters(patientId, properties.discovery().maxEncounters())) {
            Optional<EncounterRecord> record = records.record(encounterId);
            if (record.isEmpty()) {
                continue;
            }
            List<String> types = records.available(record.get()).stream().map(HiType::code).sorted().toList();
            if (types.isEmpty()) {
                continue;
            }
            CareContext context = careContexts.upsertDiscovered(patientId, encounterId,
                    careContextService.display(record.get()), types);
            boolean linked = "LINKED".equals(context.linkStatus());
            boolean inFlight = "PENDING".equals(context.linkStatus()) && context.linkRequestId() != null;
            if (!linked && !inFlight) {
                offered.add(context.id());
            }
        }
        return offered;
    }

    private static PatientMatcher.Query query(String address, JsonNode patient) {
        String mobile = null;
        String abhaNumber = null;
        for (JsonNode identifier : patient.path("verifiedIdentifiers")) {
            String type = Json.text(identifier, "type");
            String value = Json.text(identifier, "value");
            if ("MOBILE".equals(type) && value != null) {
                String digits = value.replaceAll("\\D", "");
                mobile = digits.length() >= 10 ? digits.substring(digits.length() - 10) : null;
            } else if (type != null && ABHA_NUMBER_TYPES.contains(type)) {
                abhaNumber = AbhaNumbers.digits(value);
            }
        }
        String uhid = null;
        for (JsonNode identifier : patient.path("unverifiedIdentifiers")) {
            if ("MR".equals(Json.text(identifier, "type"))) {
                uhid = Json.text(identifier, "value");
            }
        }
        String gender = Json.text(patient, "gender");
        Integer year = patient.path("yearOfBirth").canConvertToInt() && patient.path("yearOfBirth").asInt() > 0
                ? patient.path("yearOfBirth").asInt()
                : Json.text(patient, "yearOfBirth") != null && Json.text(patient, "yearOfBirth").matches("\\d{4}")
                ? Integer.valueOf(Json.text(patient, "yearOfBirth")) : null;
        return new PatientMatcher.Query(address, abhaNumber, mobile, uhid == null ? null : uhid.toUpperCase(),
                Json.text(patient, "name"), gender == null ? null : gender.substring(0, 1).toUpperCase(), year);
    }

    private void answerDiscovery(UUID requestId, Discovery discovery) {
        switch (discovery.outcome()) {
            case "MATCHED" -> {
                PatientDirectory.Patient patient = patients.find(discovery.patientId()).orElseThrow();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("transactionId", discovery.transactionId().toString());
                body.put("patient", CareContextGroups.byHiType(patient.uhid(), patient.fullName(),
                        careContexts.byIds(discovery.careContextIds())));
                body.put("matchedBy", discovery.matchedBy());
                acks.send(AbdmPaths.ON_DISCOVER, requestId, body, discovery.transactionId());
            }
            case "AMBIGUOUS" -> discoverError(requestId, discovery.transactionId(), CallbackAck.AMBIGUOUS,
                    "More than one patient matches; please visit the hospital to link your records");
            case "NO_RECORDS" -> discoverError(requestId, discovery.transactionId(), CallbackAck.NOT_FOUND,
                    "No records left to link at this hospital");
            default -> discoverError(requestId, discovery.transactionId(), CallbackAck.NOT_FOUND,
                    "No matching patient found at this hospital");
        }
    }

    private void discoverError(UUID requestId, UUID transactionId, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (transactionId != null) {
            body.put("transactionId", transactionId.toString());
        }
        body.put("error", CallbackAck.error(code, message));
        acks.send(AbdmPaths.ON_DISCOVER, requestId, body, transactionId);
    }

    // ------------------------------------------------------------------ link init (OTP)

    @Transactional
    public void onLinkInit(JsonNode body, UUID requestId) {
        UUID transactionId = Json.uuid(body, "transactionId");
        Optional<Session> retried = linking.sessionByRequest(requestId);
        if (retried.isPresent()) {
            // ABDM re-sent an init we accepted: answer again, without a second SMS.
            Session session = retried.get();
            String hint = patients.find(session.patientId()).map(p -> maskedMobile(p.mobile())).orElse(null);
            tasks.afterCommit("on-init", () -> answerInit(requestId, session, hint));
            return;
        }
        String problem;
        int code = CallbackAck.INVALID_REQUEST;
        Discovery discovery = transactionId == null ? null : linking.discovery(transactionId).orElse(null);
        String address = AbhaNumbers.address(Json.text(body, "abhaAddress"));
        if (discovery == null || !"MATCHED".equals(discovery.outcome())) {
            problem = "Unknown discovery transaction";
            code = CallbackAck.NOT_FOUND;
        } else if (!discovery.expiresAt().isAfter(time.now())) {
            problem = "Discovery has expired; search again";
        } else if (address != null && !address.equalsIgnoreCase(discovery.abhaAddress())) {
            problem = "ABHA address differs from the one that discovered these records";
        } else {
            problem = null;
        }

        PatientDirectory.Patient patient = problem == null ? patients.find(discovery.patientId()).orElse(null) : null;
        List<UUID> requested = new ArrayList<>();
        if (problem == null) {
            Set<String> references = new LinkedHashSet<>();
            boolean patientMatches = true;
            for (JsonNode group : body.path("patient")) {
                String reference = Json.text(group, "referenceNumber");
                patientMatches &= reference == null || patient != null && reference.equalsIgnoreCase(patient.uhid());
                group.path("careContexts").forEach(c -> {
                    String ref = Json.text(c, "referenceNumber");
                    if (ref != null) {
                        references.add(ref);
                    }
                });
            }
            for (String reference : references) {
                careContexts.byReference(reference).map(CareContext::id).ifPresent(requested::add);
            }
            if (!patientMatches || references.isEmpty() || requested.size() != references.size()
                    || !discovery.careContextIds().containsAll(requested)) {
                problem = "Only care contexts offered in this discovery can be linked";
            } else if (patient == null || patient.mobile() == null) {
                problem = "No mobile number is registered for OTP; please visit the hospital";
                code = CallbackAck.UNAVAILABLE;
            } else if (!otp.isConfigured()) {
                problem = "This hospital cannot send OTPs yet; please visit the hospital to link your records";
                code = CallbackAck.UNAVAILABLE;
            } else if (linking.sessionsSince(patient.id(), time.now().minus(Duration.ofHours(1)))
                    >= properties.otp().maxPerHour()) {
                problem = "Too many OTP requests; try again later";
                code = CallbackAck.TOO_MANY_REQUESTS;
            }
        }
        if (problem != null) {
            String reason = problem;
            int errorCode = code;
            tasks.afterCommit("on-init", () -> initError(requestId, transactionId, errorCode, reason));
            return;
        }

        String oneTimePassword = String.format("%06d", RANDOM.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        Session session = linking.createSession(UUID.randomUUID().toString(), transactionId, requestId,
                discovery.abhaAddress(), patient.id(), requested, salt, hash(salt, oneTimePassword),
                time.now().plus(properties.otp().validity()));
        String mobile = patient.mobile();
        tasks.afterCommit("on-init", () -> {
            try {
                otp.send(mobile, oneTimePassword);
            } catch (OtpSender.DeliveryFailed e) {
                linking.close(session.id(), "FAILED", e.getMessage());
                initError(requestId, transactionId, CallbackAck.UNAVAILABLE, "The OTP could not be sent; try again later");
                return;
            }
            answerInit(requestId, session, maskedMobile(mobile));
        });
    }

    private void answerInit(UUID requestId, Session session, String hint) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("communicationMedium", "MOBILE");
        if (hint != null) {
            meta.put("communicationHint", hint);
        }
        meta.put("communicationExpiry", DateTimeFormatter.ISO_INSTANT.format(session.expiresAt().truncatedTo(ChronoUnit.MILLIS)));
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("referenceNumber", session.linkReference());
        link.put("authenticationType", "DIRECT");
        link.put("meta", meta);
        acks.send(AbdmPaths.ON_LINK_INIT, requestId,
                Map.of("transactionId", session.transactionId().toString(), "link", link), session.transactionId());
    }

    private void initError(UUID requestId, UUID transactionId, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (transactionId != null) {
            body.put("transactionId", transactionId.toString());
        }
        body.put("error", CallbackAck.error(code, message));
        acks.send(AbdmPaths.ON_LINK_INIT, requestId, body, transactionId);
    }

    // ------------------------------------------------------------------ link confirm

    @Transactional
    public void onLinkConfirm(JsonNode body, UUID requestId) {
        String reference = Json.text(body, "confirmation.linkRefNumber");
        String token = Json.text(body, "confirmation.token");
        Session session = reference == null ? null : linking.lockSession(reference).orElse(null);
        String problem = null;
        int code = CallbackAck.INVALID_OTP;
        if (session == null || token == null) {
            problem = "Unknown link request";
            code = CallbackAck.NOT_FOUND;
        } else if (!"PENDING".equals(session.status())) {
            problem = "This link request is closed; start again from the app";
            code = CallbackAck.INVALID_REQUEST;
        } else if (!session.expiresAt().isAfter(time.now())) {
            linking.close(session.id(), "EXPIRED", "OTP expired");
            problem = "The OTP has expired; start again from the app";
        } else if (!MessageDigest.isEqual(hash(session.otpSalt(), token).getBytes(StandardCharsets.US_ASCII),
                session.otpHash().getBytes(StandardCharsets.US_ASCII))) {
            boolean exhausted = session.attempts() + 1 >= properties.otp().maxAttempts();
            linking.countFailedAttempt(session.id(), exhausted);
            problem = exhausted ? "Wrong OTP entered too many times; start again from the app" : "Wrong OTP";
        }
        if (problem != null) {
            String reason = problem;
            int errorCode = code;
            tasks.afterCommit("on-confirm", () -> acks.send(AbdmPaths.ON_LINK_CONFIRM, requestId,
                    Map.of("error", CallbackAck.error(errorCode, reason)), null));
            return;
        }

        Instant now = time.now();
        careContexts.markLinkedByPatient(session.careContextIds(), session.abhaAddress(), now);
        linking.close(session.id(), "LINKED", null);
        List<CareContext> linked = careContexts.byIds(session.careContextIds()).stream()
                .filter(c -> "LINKED".equals(c.linkStatus()) && session.abhaAddress().equalsIgnoreCase(c.linkedAbhaAddress()))
                .toList();
        PatientDirectory.Patient patient = patients.find(session.patientId()).orElseThrow();
        audit.record(Action.SHARE, "care_context", session.linkReference(), patient.id(),
                Map.of("action", "linked_by_patient", "careContexts", linked.size()));
        List<Map<String, Object>> groups = CareContextGroups.byHiType(patient.uhid(), patient.fullName(), linked);
        tasks.afterCommit("on-confirm", () -> acks.send(AbdmPaths.ON_LINK_CONFIRM, requestId,
                Map.of("patient", groups), session.transactionId()));
    }

    // ------------------------------------------------------------------ helpers

    static String hash(String salt, String oneTimePassword) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + ":" + oneTimePassword).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String maskedMobile(String mobile) {
        return mobile == null || mobile.length() < 4 ? null : "******" + mobile.substring(mobile.length() - 4);
    }
}
