package com.syncra.hims.patient.opd;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.patient.security.Roles;
import com.syncra.hims.patient.opd.dto.QueueResponse;
import com.syncra.hims.patient.opd.dto.ReferenceResponses.DepartmentItem;
import com.syncra.hims.patient.opd.dto.ReferenceResponses.DoctorItem;
import com.syncra.hims.patient.opd.dto.RegisterVisitRequest;
import com.syncra.hims.patient.opd.dto.VisitResponse;
import com.syncra.hims.patient.reference.ReferenceData;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** OPD token issue and queue management: {@code /api/v1/opd}. */
@RestController
@RequestMapping("/api/v1/opd")
public class OpdController {

    private final OpdService opd;
    private final ReferenceData reference;
    private final AuditService audit;

    public OpdController(OpdService opd, ReferenceData reference, AuditService audit) {
        this.opd = opd;
        this.reference = reference;
        this.audit = audit;
    }

    @GetMapping("/departments")
    @PreAuthorize(Roles.ANY_STAFF)
    public List<DepartmentItem> departments() {
        return reference.opdDepartments().stream().map(DepartmentItem::from).toList();
    }

    @GetMapping("/departments/{code}/doctors")
    @PreAuthorize(Roles.ANY_STAFF)
    public List<DoctorItem> doctors(@PathVariable String code) {
        return reference.doctorsIn(code).stream().map(DoctorItem::from).toList();
    }

    /** Issues a token (201). */
    @PostMapping("/visits")
    @PreAuthorize(Roles.OPD_DESK)
    public ResponseEntity<VisitResponse> register(@Valid @RequestBody RegisterVisitRequest request) {
        VisitResponse visit = opd.register(request);
        return ResponseEntity.created(URI.create("/api/v1/opd/visits/" + visit.visitId())).body(visit);
    }

    @GetMapping("/visits/{visitId}")
    @PreAuthorize(Roles.OPD_VIEW)
    public VisitResponse get(@PathVariable UUID visitId) {
        VisitResponse visit = opd.get(visitId);
        audit.record(Action.READ, "opd_visit", visitId, null);
        return visit;
    }

    /** Queue board for a department (today unless {@code date} is given). */
    @GetMapping("/queue")
    @PreAuthorize(Roles.OPD_VIEW)
    public QueueResponse queue(
            @RequestParam("department") String departmentCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID doctorId) {
        QueueResponse queue = opd.queue(departmentCode, date, doctorId);
        audit.record(Action.READ, "opd_queue", queue.departmentCode(), null,
                Map.of("date", queue.date().toString(), "entries", queue.visits().size()));
        return queue;
    }

    @PostMapping("/queue/{departmentCode}/call-next")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse callNext(@PathVariable String departmentCode) {
        return opd.callNext(departmentCode);
    }

    @PostMapping("/visits/{visitId}/call")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse call(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.CALLED);
    }

    @PostMapping("/visits/{visitId}/start")
    @PreAuthorize(Roles.CONSULTATION)
    public VisitResponse start(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.IN_CONSULTATION);
    }

    @PostMapping("/visits/{visitId}/complete")
    @PreAuthorize(Roles.CONSULTATION)
    public VisitResponse complete(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.COMPLETED);
    }

    /** Parks the token, e.g. while the patient goes for an investigation. */
    @PostMapping("/visits/{visitId}/hold")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse hold(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.ON_HOLD);
    }

    /** Puts a called or held token back in the waiting line. */
    @PostMapping("/visits/{visitId}/requeue")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse requeue(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.WAITING);
    }

    @PostMapping("/visits/{visitId}/no-show")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse noShow(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.NO_SHOW);
    }

    @PostMapping("/visits/{visitId}/cancel")
    @PreAuthorize(Roles.OPD_DESK)
    public VisitResponse cancel(@PathVariable UUID visitId) {
        return opd.transition(visitId, QueueStatus.CANCELLED);
    }
}
