package com.syncra.hims.abdm.enrolment;

import com.syncra.hims.abdm.abha.AbhaClient.OtpSent;
import com.syncra.hims.abdm.abha.AbhaLinker;
import com.syncra.hims.abdm.abha.AbhaLinker.LinkedAbha;
import com.syncra.hims.abdm.abha.AbhaNumbers;
import com.syncra.hims.abdm.abha.AbhaProfile;
import com.syncra.hims.abdm.abha.AbhaService.ExistingPatient;
import com.syncra.hims.abdm.abha.VerificationRepository;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.enrolment.AbhaEnrolmentClient.Card;
import com.syncra.hims.abdm.enrolment.AbhaEnrolmentClient.Enrolled;
import com.syncra.hims.abdm.enrolment.EnrolmentSessions.Session;
import com.syncra.hims.abdm.enrolment.EnrolmentSessions.Stage;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Milestone 1, ABHA creation at the desk for patients who have none, using their Aadhaar:
 *
 * <ol>
 *   <li>{@link #start}: with the patient's consent, an OTP goes to their Aadhaar-linked mobile.</li>
 *   <li>{@link #create}: the OTP creates the ABHA (or returns the one this Aadhaar already has).
 *       It counts as a verification, so it can be attached to the patient record at once.</li>
 *   <li>Optional: verify a different communication mobile, choose an ABHA address, print the card.</li>
 * </ol>
 * The Aadhaar number is validated (Verhoeff), encrypted for ABHA and dropped; it is never
 * stored, logged or returned unmasked (Aadhaar Act 2016 s.29).
 */
@Service
public class AbhaEnrolmentService {

    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    /** ABDM rule: 8-18 letters or digits, with at most one dot or underscore, not at either end. */
    private static final Pattern ADDRESS = Pattern.compile("^(?=.{8,18}$)[A-Za-z0-9]+(?:[._][A-Za-z0-9]+)?$");
    private static final Set<Stage> CREATED = EnumSet.of(Stage.CREATED, Stage.MOBILE_OTP_SENT);

    public record Started(UUID enrolmentId, String aadhaar, String message) {
    }

    /**
     * @param mobileVerificationRequired the mobile given is not Aadhaar-linked: verify it to make it
     *                                   the ABHA communication mobile (the ABHA exists either way)
     */
    public record Created(UUID enrolmentId, UUID verificationId, boolean isNew, AbhaProfile profile,
                          boolean mobileVerificationRequired, ExistingPatient existingPatient, LinkedAbha linked) {
    }

    public record AddressCreated(String abhaAddress, boolean patientUpdated) {
    }

    private final AbhaEnrolmentClient abha;
    private final EnrolmentSessions sessions;
    private final EnrolmentRepository enrolments;
    private final VerificationRepository verifications;
    private final AbhaLinker linker;
    private final PatientDirectory patients;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final AbdmProperties properties;
    private final HospitalTime time;

    public AbhaEnrolmentService(AbhaEnrolmentClient abha, EnrolmentSessions sessions, EnrolmentRepository enrolments,
                                VerificationRepository verifications, AbhaLinker linker, PatientDirectory patients,
                                CurrentUser currentUser, AuditService audit, AbdmProperties properties,
                                HospitalTime time) {
        this.abha = abha;
        this.sessions = sessions;
        this.enrolments = enrolments;
        this.verifications = verifications;
        this.linker = linker;
        this.patients = patients;
        this.currentUser = currentUser;
        this.audit = audit;
        this.properties = properties;
        this.time = time;
    }

    public Started start(String aadhaarNumber, boolean patientConsent) {
        if (!patientConsent) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED",
                    "Read ABDM's consent text to the patient and record their agreement before using their Aadhaar");
        }
        String aadhaar = Aadhaar.normalise(aadhaarNumber);
        if (aadhaar == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AADHAAR",
                    "This is not a valid Aadhaar number; check the 12 digits");
        }
        OtpSent sent = abha.requestAadhaarOtp(aadhaar);
        UUID id = UUID.randomUUID();
        sessions.save(new Session(id, currentUser.subject(), sent.txnId(), Stage.OTP_SENT, Aadhaar.masked(aadhaar),
                null, null, null));
        audit.record(Action.CREATE, "abha_enrolment", id, null,
                Map.of("stage", "aadhaar_otp_sent", "consentVersion", properties.abha().enrolmentConsentVersion()));
        return new Started(id, Aadhaar.masked(aadhaar), sent.message());
    }

    /** Not transactional around the ABHA call: a failure must stay on record. */
    public Created create(UUID enrolmentId, String otp, String mobile, UUID patientId) {
        Session session = require(enrolmentId, EnumSet.of(Stage.OTP_SENT));
        requireMobile(mobile);
        String actor = currentUser.subject();
        String consent = properties.abha().enrolmentConsentVersion();
        Enrolled enrolled;
        try {
            enrolled = abha.enrolByAadhaar(session.txnId(), otp, mobile);
        } catch (AbdmCallException e) {
            enrolments.failed(enrolmentId, consent, e.getMessage(), actor);
            throw e;
        }
        AbhaProfile profile = enrolled.profile();
        UUID verificationId = verifications.success(VerificationRepository.Method.AADHAAR_OTP, profile,
                enrolled.txnId(), actor);
        enrolments.created(enrolmentId, enrolled.isNew(), profile.abhaNumber(), profile.abhaAddress(),
                enrolled.mobileVerified(), consent, verificationId, actor);
        sessions.save(session.created(enrolled.txnId(), enrolled.userToken(), profile.abhaNumber(), verificationId));
        audit.record(Action.CREATE, "abha_enrolment", enrolmentId, patientId,
                Map.of("outcome", enrolled.isNew() ? "CREATED" : "EXISTING", "mobileVerified", enrolled.mobileVerified()));

        ExistingPatient existing = patients.byAbha(profile.abhaNumber(), profile.abhaAddress())
                .map(p -> new ExistingPatient(p.id(), p.uhid(), p.fullName())).orElse(null);
        LinkedAbha linked = patientId == null ? null : linker.link(patientId, verificationId);
        return new Created(enrolmentId, verificationId, enrolled.isNew(), profile, !enrolled.mobileVerified(),
                existing, linked);
    }

    public OtpSent requestMobileOtp(UUID enrolmentId, String mobile) {
        Session session = require(enrolmentId, CREATED);
        requireMobile(mobile);
        OtpSent sent = abha.requestMobileOtp(session.txnId(), mobile);
        sessions.save(session.with(sent.txnId(), Stage.MOBILE_OTP_SENT));
        return sent;
    }

    public void verifyMobile(UUID enrolmentId, String otp) {
        Session session = require(enrolmentId, EnumSet.of(Stage.MOBILE_OTP_SENT));
        abha.verifyMobile(session.txnId(), otp);
        enrolments.mobileVerified(enrolmentId);
        sessions.save(session.with(session.txnId(), Stage.CREATED));
        audit.record(Action.UPDATE, "abha_enrolment", enrolmentId, null, Map.of("action", "mobile_verified"));
    }

    public List<String> addressSuggestions(UUID enrolmentId) {
        return abha.addressSuggestions(require(enrolmentId, CREATED).txnId());
    }

    /** Creates the patient's ABHA address and, if the ABHA is already on a patient record, puts it there too. */
    @Transactional
    public AddressCreated createAddress(UUID enrolmentId, String requested) {
        Session session = require(enrolmentId, CREATED);
        String wanted = requested == null ? "" : requested.trim();
        int suffix = wanted.indexOf('@');
        wanted = suffix >= 0 ? wanted.substring(0, suffix) : wanted;
        if (!ADDRESS.matcher(wanted).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ABHA_ADDRESS",
                    "An ABHA address has 8-18 letters or digits, with at most one dot or underscore inside");
        }
        String created = AbhaNumbers.address(abha.createAddress(session.txnId(), wanted));
        if (created == null) {
            throw AbdmCallException.invalidResponse("unusable ABHA address");
        }
        enrolments.address(enrolmentId, created);
        verifications.setAddress(session.verificationId(), created);
        boolean patientUpdated = false;
        UUID patientId = verifications.lock(session.verificationId()).map(VerificationRepository.Verification::patientId)
                .orElse(null);
        if (patientId != null) {
            PatientDirectory.Patient patient = patients.lock(patientId).orElseThrow();
            if (patient.abhaVerifiedAt() != null && session.abhaNumber().equals(patient.abhaNumber())) {
                patients.setVerifiedAbha(patientId, patient.abhaNumber(), created, time.now(), currentUser.subject());
                patientUpdated = true;
            }
        }
        audit.record(Action.UPDATE, "abha_enrolment", enrolmentId, patientId, Map.of("action", "abha_address_created"));
        return new AddressCreated(created, patientUpdated);
    }

    public Card card(UUID enrolmentId) {
        Session session = require(enrolmentId, CREATED);
        Card card = abha.card(session.userToken());
        UUID patientId = verifications.lock(session.verificationId()).map(VerificationRepository.Verification::patientId)
                .orElse(null);
        audit.record(Action.PRINT, "abha_card", enrolmentId, patientId, null);
        return card;
    }

    private Session require(UUID enrolmentId, Set<Stage> stages) {
        Session session = sessions.find(enrolmentId)
                .filter(s -> s.actor().equals(currentUser.subject()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENROLMENT_NOT_FOUND",
                        "This ABHA enrolment has expired or was started by someone else; start again"));
        if (!stages.contains(session.stage())) {
            throw ApiException.conflict("ENROLMENT_STAGE", switch (session.stage()) {
                case OTP_SENT -> "Verify the Aadhaar OTP first";
                case CREATED -> "The ABHA is already created" + (stages.contains(Stage.MOBILE_OTP_SENT)
                        ? "" : "; request a mobile OTP first");
                case MOBILE_OTP_SENT -> "Enter the mobile OTP first";
            });
        }
        return session;
    }

    private static void requireMobile(String mobile) {
        if (mobile == null || !MOBILE.matcher(mobile).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MOBILE", "Enter a 10-digit Indian mobile number");
        }
    }
}
