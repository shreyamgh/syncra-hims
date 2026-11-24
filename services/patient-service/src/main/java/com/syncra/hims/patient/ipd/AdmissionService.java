package com.syncra.hims.patient.ipd;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.encounter.Encounter;
import com.syncra.hims.patient.encounter.EncounterRepository;
import com.syncra.hims.patient.ipd.dto.AdmissionResponse;
import com.syncra.hims.patient.ipd.dto.AdmitRequest;
import com.syncra.hims.patient.ipd.dto.BedMoveRequest;
import com.syncra.hims.patient.ipd.dto.DischargeRequest;
import com.syncra.hims.patient.reference.Department;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import com.syncra.hims.patient.registration.Patient;
import com.syncra.hims.patient.registration.PatientService;
import com.syncra.hims.patient.registration.Uhid;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admissions, bed allocation/transfer and discharge.
 *
 * <p>The database is the final arbiter of bed availability: assigning a bed that
 * is occupied, blocked or in a ward of another gender fails in the
 * {@code ipd.bed_assignment} trigger or exclusion constraints and the whole
 * operation rolls back. Each step is flushed explicitly so those checks run in
 * a known order (release before re-assign, release before discharge).
 */
@Service
public class AdmissionService {

    private static final EnumSet<AdmissionStatus> ACTIVE =
            EnumSet.of(AdmissionStatus.ADMITTED, AdmissionStatus.DISCHARGE_INITIATED);

    private final AdmissionRepository admissions;
    private final BedAssignmentRepository assignments;
    private final BedRepository beds;
    private final EncounterRepository encounters;
    private final PatientService patients;
    private final ReferenceData reference;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    public AdmissionService(AdmissionRepository admissions, BedAssignmentRepository assignments, BedRepository beds,
                            EncounterRepository encounters, PatientService patients, ReferenceData reference,
                            CurrentUser currentUser, AuditService audit, HospitalTime time) {
        this.admissions = admissions;
        this.assignments = assignments;
        this.beds = beds;
        this.encounters = encounters;
        this.patients = patients;
        this.reference = reference;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public AdmissionResponse admit(AdmitRequest request) {
        Patient patient = patients.requireCareable(Uhid.parse(request.uhid()));
        admissions.findFirstByPatient_IdAndStatusIn(patient.getId(), ACTIVE).ifPresent(existing -> {
            throw ApiException.conflict("ALREADY_ADMITTED",
                    "Patient is already admitted under " + existing.getAdmissionNumber());
        });
        Department department = reference.requireDepartment(request.departmentCode());
        Staff doctor = reference.requireDoctor(request.admittingDoctorId(), null);
        UUID tariffPlanId = reference.requireTariffPlan(request.tariffPlanCode());
        Instant now = time.now();

        Encounter encounter = encounters.save(Encounter.open(patient, Encounter.Type.IPD, department, doctor,
                Encounter.Status.IN_PROGRESS, now, null, Boolean.TRUE.equals(request.medicoLegal())));
        Admission admission = admissions.saveAndFlush(
                Admission.admit(encounter, department, doctor, details(request, tariffPlanId), now));

        BedAssignment bed = null;
        if (request.bedCode() != null && !request.bedCode().isBlank()) {
            bed = assign(admission, requireBed(request.bedCode()), AssignmentReason.ADMISSION, now);
        }
        audit.record(Action.CREATE, "admission", admission.getId(), patient.getId(),
                bed == null ? Map.of("number", admission.getAdmissionNumber())
                        : Map.of("number", admission.getAdmissionNumber(), "bed", bed.getBed().getBedCode()));
        return toResponse(admission, bed);
    }

    /** Allocates the first bed, or moves the patient to another bed at the same instant. */
    @Transactional
    public AdmissionResponse moveBed(UUID admissionId, BedMoveRequest request) {
        Admission admission = lock(admissionId);
        admission.requireActive();
        Bed target = requireBed(request.bedCode());
        Optional<BedAssignment> current = assignments.findOpen(admissionId);
        if (current.isPresent() && current.get().getBed().getId().equals(target.getId())) {
            throw ApiException.conflict("SAME_BED", "Patient is already in bed " + target.getBedCode());
        }
        AssignmentReason reason = current.isEmpty()
                ? AssignmentReason.ADMISSION
                : (request.reason() == null || request.reason() == AssignmentReason.ADMISSION
                        ? AssignmentReason.TRANSFER : request.reason());

        Instant now = time.now();
        current.ifPresent(open -> {
            open.release(now, currentUser.subject());
            assignments.saveAndFlush(open);
        });
        BedAssignment moved = assign(admission, target, reason, now);
        audit.record(Action.UPDATE, "admission", admissionId, admission.getPatient().getId(),
                Map.of("action", "bed_" + reason.name().toLowerCase(), "bed", target.getBedCode(),
                        "from", current.map(a -> a.getBed().getBedCode()).orElse("none")));
        return toResponse(admission, moved);
    }

    @Transactional
    public AdmissionResponse initiateDischarge(UUID admissionId) {
        Admission admission = lock(admissionId);
        admission.initiateDischarge(time.now());
        admissions.saveAndFlush(admission);
        audit.record(Action.UPDATE, "admission", admissionId, admission.getPatient().getId(),
                Map.of("action", "discharge_initiated"));
        return toResponse(admission, assignments.findOpen(admissionId).orElse(null));
    }

    /** Releases the bed (it goes to CLEANING) and closes the stay and its encounter. */
    @Transactional
    public AdmissionResponse discharge(UUID admissionId, DischargeRequest request) {
        Admission admission = lock(admissionId);
        admission.requireActive();
        Instant at = request.dischargedAt() == null ? time.now() : request.dischargedAt();

        Optional<BedAssignment> open = assignments.findOpen(admissionId);
        if (open.isPresent()) {
            if (!at.isAfter(open.get().getAssignedAt())) {
                throw ApiException.unprocessable("INVALID_TIME",
                        "Discharge time must be after the patient moved into bed " + open.get().getBed().getBedCode());
            }
            open.get().release(at, currentUser.subject());
            assignments.saveAndFlush(open.get());
        }
        admission.close(request.outcome(), at);
        admissions.saveAndFlush(admission);
        audit.record(Action.UPDATE, "admission", admissionId, admission.getPatient().getId(),
                Map.of("action", "discharge", "outcome", request.outcome().name()));
        return toResponse(admission, null);
    }

    @Transactional(readOnly = true)
    public AdmissionResponse get(UUID admissionId) {
        Admission admission = admissions.findDetailed(admissionId)
                .orElseThrow(() -> ApiException.notFound("Admission", admissionId));
        return toResponse(admission, assignments.findOpen(admissionId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<AdmissionResponse> active() {
        return admissions.findByStatuses(ACTIVE).stream()
                .map(a -> toResponse(a, assignments.findOpen(a.getId()).orElse(null)))
                .toList();
    }

    private BedAssignment assign(Admission admission, Bed bed, AssignmentReason reason, Instant at) {
        // The insert trigger re-checks availability and the ward's gender restriction under a row lock.
        return assignments.saveAndFlush(BedAssignment.assign(admission, bed, reason, at, currentUser.subject()));
    }

    private Admission lock(UUID admissionId) {
        admissions.lockById(admissionId).orElseThrow(() -> ApiException.notFound("Admission", admissionId));
        return admissions.findDetailed(admissionId).orElseThrow();
    }

    private Bed requireBed(String bedCode) {
        return beds.findActiveByCode(bedCode.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Bed", bedCode));
    }

    private static Admission.Details details(AdmitRequest r, UUID tariffPlanId) {
        AdmitRequest.Attendant attendant = r.attendant();
        AdmitRequest.Payer payer = r.payer();
        return new Admission.Details(r.admissionType(), r.provisionalDiagnosis(), r.expectedDischargeDate(),
                tariffPlanId,
                payer == null ? null : payer.insurerOrScheme(),
                payer == null ? null : payer.policyNumber(),
                payer == null ? null : payer.preauthNumber(),
                payer == null ? null : payer.preauthAmount(),
                attendant == null ? null : attendant.name(),
                attendant == null ? null : attendant.relation(),
                attendant == null ? null : attendant.mobile());
    }

    private AdmissionResponse toResponse(Admission admission, BedAssignment openBed) {
        return AdmissionResponse.from(admission, openBed, patients.ageOf(admission.getPatient()));
    }
}
