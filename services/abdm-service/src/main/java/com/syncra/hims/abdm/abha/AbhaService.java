package com.syncra.hims.abdm.abha;

import com.syncra.hims.abdm.abha.AbhaClient.Login;
import com.syncra.hims.abdm.abha.AbhaClient.OtpSent;
import com.syncra.hims.abdm.abha.AbhaLinker.LinkedAbha;
import com.syncra.hims.abdm.abha.VerificationRepository.Method;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Milestone 1 at the registration desk: verify that the patient holds the ABHA they
 * quote (OTP), show staff the ABDM profile to register or check against, and attach the
 * verified ABHA to the patient record.
 */
@Service
public class AbhaService {

    public record OtpRequested(String txnId, String message, String abhaNumber) {
    }

    /**
     * @param verificationId  use with {@code POST /patients/{id}/abha} within 30 minutes
     * @param existingPatient a patient already registered with this ABHA, if any (avoid duplicates)
     * @param linked          set when the request named a patient and the ABHA was attached
     */
    public record Verified(UUID verificationId, AbhaProfile profile, ExistingPatient existingPatient, LinkedAbha linked) {
    }

    public record ExistingPatient(UUID patientId, String uhid, String name) {
    }

    private final AbhaClient abha;
    private final VerificationRepository verifications;
    private final AbhaLinker linker;
    private final PatientDirectory patients;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public AbhaService(AbhaClient abha, VerificationRepository verifications, AbhaLinker linker,
                       PatientDirectory patients, CurrentUser currentUser, AuditService audit) {
        this.abha = abha;
        this.verifications = verifications;
        this.linker = linker;
        this.patients = patients;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public OtpRequested requestOtp(AbhaLoginMethod method, String abhaNumber) {
        String digits = requireAbhaNumber(abhaNumber);
        OtpSent sent = abha.requestOtp(method, digits);
        return new OtpRequested(sent.txnId(), sent.message(), AbhaNumbers.masked(digits));
    }

    /** Verifies the OTP; each attempt is recorded. Not transactional: a failure must stay on record. */
    public Verified verify(AbhaLoginMethod method, String txnId, String otp, String abhaNumber, UUID patientId) {
        String digits = requireAbhaNumber(abhaNumber);
        Method recorded = method == AbhaLoginMethod.AADHAAR_OTP ? Method.AADHAAR_OTP : Method.MOBILE_OTP;
        String actor = currentUser.subject();
        AbhaProfile profile;
        try {
            Login login = abha.verifyOtp(method, txnId, otp);
            profile = abha.profile(login);
        } catch (AbdmCallException e) {
            verifications.failure(recorded, digits, txnId, e.getMessage(), actor);
            throw e;
        }
        if (!digits.equals(profile.abhaNumber())) {
            verifications.failure(recorded, digits, txnId, "ABDM returned a different ABHA number", actor);
            throw ApiException.unprocessable("ABHA_MISMATCH", "ABDM verified a different ABHA number than requested");
        }
        if (!profile.isActive()) {
            verifications.failure(recorded, digits, txnId, "ABHA status " + profile.status(), actor);
            throw ApiException.unprocessable("ABHA_INACTIVE", "This ABHA is " + profile.status().toLowerCase()
                    + " and cannot be linked");
        }
        UUID verificationId = verifications.success(recorded, profile, txnId, actor);
        audit.record(Action.CREATE, "abha_verification", verificationId, patientId, Map.of("method", recorded.name()));

        ExistingPatient existing = patients.byAbha(profile.abhaNumber(), profile.abhaAddress())
                .map(p -> new ExistingPatient(p.id(), p.uhid(), p.fullName())).orElse(null);
        LinkedAbha linked = patientId == null ? null : linker.link(patientId, verificationId);
        return new Verified(verificationId, profile, existing, linked);
    }

    public LinkedAbha link(UUID patientId, UUID verificationId) {
        return linker.link(patientId, verificationId);
    }

    private static String requireAbhaNumber(String value) {
        String digits = AbhaNumbers.digits(value);
        if (digits == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ABHA_NUMBER", "Enter the 14-digit ABHA number");
        }
        return digits;
    }
}
