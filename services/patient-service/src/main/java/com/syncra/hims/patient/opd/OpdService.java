package com.syncra.hims.patient.opd;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.encounter.Encounter;
import com.syncra.hims.patient.encounter.EncounterRepository;
import com.syncra.hims.patient.opd.dto.QueueResponse;
import com.syncra.hims.patient.opd.dto.RegisterVisitRequest;
import com.syncra.hims.patient.opd.dto.VisitResponse;
import com.syncra.hims.patient.reference.Department;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import com.syncra.hims.patient.registration.Patient;
import com.syncra.hims.patient.registration.PatientService;
import com.syncra.hims.patient.registration.Uhid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OPD registration (token issue) and the per-department queue. */
@Service
public class OpdService {

    private static final Comparator<OpdVisit> BOARD_ORDER = Comparator
            .comparingInt((OpdVisit v) -> v.getQueueStatus().displayOrder())
            .thenComparingInt(v -> v.getPriority().rank())
            .thenComparing(OpdVisit::getTokenNumber);

    private final OpdVisitRepository visits;
    private final EncounterRepository encounters;
    private final PatientService patients;
    private final ReferenceData reference;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    public OpdService(OpdVisitRepository visits, EncounterRepository encounters, PatientService patients,
                      ReferenceData reference, CurrentUser currentUser, AuditService audit, HospitalTime time) {
        this.visits = visits;
        this.encounters = encounters;
        this.patients = patients;
        this.reference = reference;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    /** Opens an OPD encounter and issues the next token in the department's daily sequence. */
    @Transactional
    public VisitResponse register(RegisterVisitRequest request) {
        Patient patient = patients.requireCareable(Uhid.parse(request.uhid()));
        Department department = reference.requireOpdDepartment(request.departmentCode());
        Staff doctor = request.doctorId() == null ? null : reference.requireDoctor(request.doctorId(), department);
        LocalDate today = time.today();

        visits.findLiveToken(patient.getId(), department.getId(), today).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "LIVE_TOKEN_EXISTS",
                    "Patient already holds token " + existing.getTokenNumber() + " in " + department.getName()
                            + " today",
                    Map.of("visitId", existing.getId(), "tokenNumber", existing.getTokenNumber()));
        });
        if (request.followUpOfVisitId() != null) {
            OpdVisit previous = visits.findById(request.followUpOfVisitId())
                    .orElseThrow(() -> ApiException.notFound("Visit", request.followUpOfVisitId()));
            if (!previous.getPatient().getId().equals(patient.getId())) {
                throw ApiException.unprocessable("FOLLOW_UP_MISMATCH", "The earlier visit belongs to another patient");
            }
        }

        VisitType type = request.typeOrDefault();
        VisitPriority priority = request.priority() != null
                ? request.priority()
                : VisitPriority.defaultFor(type, patients.ageOf(patient));
        Instant now = time.now();

        Encounter encounter = encounters.save(Encounter.open(patient,
                type == VisitType.EMERGENCY ? Encounter.Type.EMERGENCY : Encounter.Type.OPD,
                department, doctor, Encounter.Status.ARRIVED, now, request.referredBy(),
                Boolean.TRUE.equals(request.medicoLegal())));
        OpdVisit visit = visits.saveAndFlush(OpdVisit.register(encounter, department, doctor, today, type, priority,
                now, request.followUpOfVisitId()));

        audit.record(Action.CREATE, "opd_visit", visit.getId(), patient.getId(),
                Map.of("department", department.getCode(), "token", visit.getTokenNumber()));
        return toResponse(visit);
    }

    @Transactional(readOnly = true)
    public VisitResponse get(UUID visitId) {
        return toResponse(require(visitId));
    }

    @Transactional(readOnly = true)
    public QueueResponse queue(String departmentCode, LocalDate date, UUID doctorId) {
        Department department = reference.requireDepartment(departmentCode);
        LocalDate day = date == null ? time.today() : date;
        List<OpdVisit> board = visits.findQueue(department.getCode(), day).stream()
                .filter(v -> doctorId == null || v.getDoctor() == null || v.getDoctor().getId().equals(doctorId))
                .sorted(BOARD_ORDER)
                .toList();
        Map<QueueStatus, Long> counts = new EnumMap<>(QueueStatus.class);
        board.forEach(v -> counts.merge(v.getQueueStatus(), 1L, Long::sum));
        return new QueueResponse(department.getCode(), department.getName(), day, counts,
                board.stream().map(this::toResponse).toList());
    }

    /**
     * Calls the next patient: for a doctor, the next unassigned token or one booked
     * with them; for the desk/nurse, the next token in the department.
     */
    @Transactional
    public VisitResponse callNext(String departmentCode) {
        Department department = reference.requireOpdDepartment(departmentCode);
        Optional<Staff> doctor = actingDoctor();
        LocalDate today = time.today();
        OpdVisit visit = doctor
                .map(d -> visits.lockNextWaitingForDoctor(department.getCode(), today, d.getId()))
                .orElseGet(() -> visits.lockNextWaiting(department.getCode(), today))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUEUE_EMPTY",
                        "No patients are waiting in " + department.getName()));
        return applyTransition(visit, QueueStatus.CALLED, doctor.orElse(null));
    }

    @Transactional
    public VisitResponse transition(UUID visitId, QueueStatus target) {
        OpdVisit visit = require(visitId);
        Staff doctor = target == QueueStatus.CALLED || target == QueueStatus.IN_CONSULTATION
                ? actingDoctor().orElse(null)
                : null;
        return applyTransition(visit, target, doctor);
    }

    private VisitResponse applyTransition(OpdVisit visit, QueueStatus target, Staff doctor) {
        QueueStatus from = visit.getQueueStatus();
        visit.moveTo(target, time.now(), doctor);
        visits.saveAndFlush(visit);
        audit.record(Action.UPDATE, "opd_visit", visit.getId(), visit.getPatient().getId(),
                Map.of("from", from.name(), "to", target.name()));
        return toResponse(visit);
    }

    /**
     * Completes a visit when the doctor signs the consultation, walking it through
     * any steps the doctor skipped (a token can be signed off straight from the queue).
     */
    @Transactional
    public void completeFromConsultation(UUID visitId, Staff doctor) {
        OpdVisit visit = require(visitId);
        QueueStatus from = visit.getQueueStatus();
        Instant now = time.now();
        if (from == QueueStatus.WAITING || from == QueueStatus.ON_HOLD) {
            visit.moveTo(QueueStatus.CALLED, now, doctor);
        }
        if (visit.getQueueStatus() == QueueStatus.CALLED) {
            visit.moveTo(QueueStatus.IN_CONSULTATION, now, doctor);
        }
        visit.moveTo(QueueStatus.COMPLETED, now, doctor);
        visits.saveAndFlush(visit);
        audit.record(Action.UPDATE, "opd_visit", visit.getId(), visit.getPatient().getId(),
                Map.of("from", from.name(), "to", QueueStatus.COMPLETED.name(), "via", "consultation"));
    }

    private Optional<Staff> actingDoctor() {
        return currentUser.hasRole("DOCTOR") ? reference.currentStaff().filter(Staff::isDoctor) : Optional.empty();
    }

    private OpdVisit require(UUID visitId) {
        return visits.findDetailed(visitId).orElseThrow(() -> ApiException.notFound("Visit", visitId));
    }

    private VisitResponse toResponse(OpdVisit visit) {
        return VisitResponse.from(visit, patients.ageOf(visit.getPatient()));
    }
}
