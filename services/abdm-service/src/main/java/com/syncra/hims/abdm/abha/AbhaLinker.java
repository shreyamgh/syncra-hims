package com.syncra.hims.abdm.abha;

import com.syncra.hims.abdm.abha.VerificationRepository.Verification;
import com.syncra.hims.abdm.patient.PatientDirectory;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaches a verified ABHA to a patient record. Only a successful verification made in the
 * last {@value #VALIDITY_MINUTES} minutes by the same member of staff can be used, once; the
 * patient's gender and year of birth must agree with ABDM's (names are not compared: spelling
 * and order of Indian names differ too often between records).
 */
@Component
public class AbhaLinker {

    static final int VALIDITY_MINUTES = 30;
    private static final int ESTIMATED_DOB_TOLERANCE_YEARS = 2;

    /** The ABHA identity now on the patient record. */
    public record LinkedAbha(UUID patientId, String uhid, String abhaNumber, String abhaAddress, Instant verifiedAt) {
    }

    private final VerificationRepository verifications;
    private final PatientDirectory patients;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    public AbhaLinker(VerificationRepository verifications, PatientDirectory patients, CurrentUser currentUser,
                      AuditService audit, HospitalTime time) {
        this.verifications = verifications;
        this.patients = patients;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public LinkedAbha link(UUID patientId, UUID verificationId) {
        Verification verification = verifications.lock(verificationId)
                .orElseThrow(() -> ApiException.notFound("ABHA verification", verificationId));
        if (!"SUCCESS".equals(verification.outcome())) {
            throw ApiException.unprocessable("VERIFICATION_FAILED", "This ABHA verification did not succeed");
        }
        if (!Objects.equals(verification.performedBy(), currentUser.subject())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_VERIFICATION",
                    "Only the person who verified the ABHA can link it");
        }
        if (verification.performedAt().isBefore(time.now().minus(Duration.ofMinutes(VALIDITY_MINUTES)))) {
            throw ApiException.unprocessable("VERIFICATION_EXPIRED",
                    "The ABHA verification is older than " + VALIDITY_MINUTES + " minutes; verify again");
        }
        if (verification.patientId() != null && !verification.patientId().equals(patientId)) {
            throw ApiException.conflict("VERIFICATION_USED", "This ABHA verification was used for another patient");
        }

        PatientDirectory.Patient patient = patients.lock(patientId)
                .orElseThrow(() -> ApiException.notFound("Patient", patientId));
        if (!patient.active()) {
            throw ApiException.unprocessable("PATIENT_INACTIVE", "Patient " + patient.uhid() + " is inactive or merged");
        }
        List<String> mismatches = mismatches(patient, verification);
        if (!mismatches.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMOGRAPHIC_MISMATCH",
                    "The ABHA profile does not match patient " + patient.uhid() + " (" + String.join(", ", mismatches)
                            + "); correct the registration or verify the right ABHA",
                    Map.of("fields", mismatches));
        }
        if (patient.abhaVerifiedAt() != null
                && (patient.abhaNumber() != null && !patient.abhaNumber().equals(verification.abhaNumber())
                    || verification.abhaNumber() == null && patient.abhaAddress() != null
                       && !patient.abhaAddress().equalsIgnoreCase(verification.abhaAddress()))) {
            throw ApiException.conflict("ANOTHER_ABHA_LINKED",
                    "Patient " + patient.uhid() + " already has a different verified ABHA");
        }

        // Only verified values remain on the record: an unverified, typed-in number or address is replaced.
        Instant now = time.now();
        String address = verification.abhaAddress();
        patients.setVerifiedAbha(patientId, verification.abhaNumber(), address, now, currentUser.subject());
        verifications.attach(verificationId, patientId);
        audit.record(Action.UPDATE, "patient", patientId, patientId,
                Map.of("action", "abha_verified", "method", verification.method()));
        return new LinkedAbha(patientId, patient.uhid(), verification.abhaNumber(), address, now);
    }

    static List<String> mismatches(PatientDirectory.Patient patient, Verification verification) {
        List<String> fields = new ArrayList<>();
        String abhaGender = verification.gender();
        if (abhaGender != null && !"U".equals(abhaGender) && !"U".equals(patient.gender())
                && !abhaGender.equals(patient.gender())) {
            fields.add("gender");
        }
        Integer year = verification.yearOfBirth();
        if (year != null && patient.dateOfBirth() != null) {
            int difference = Math.abs(patient.dateOfBirth().getYear() - year);
            if (difference > (patient.dobEstimated() ? ESTIMATED_DOB_TOLERANCE_YEARS : 0)) {
                fields.add("year of birth");
            }
        }
        return fields;
    }
}
