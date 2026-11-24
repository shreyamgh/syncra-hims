package com.syncra.hims.patient.ipd;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.patient.security.Roles;
import com.syncra.hims.patient.ipd.dto.AdmissionResponse;
import com.syncra.hims.patient.ipd.dto.AdmitRequest;
import com.syncra.hims.patient.ipd.dto.BedMoveRequest;
import com.syncra.hims.patient.ipd.dto.BedStatusRequest;
import com.syncra.hims.patient.ipd.dto.BedViews.AvailableBed;
import com.syncra.hims.patient.ipd.dto.BedViews.BedState;
import com.syncra.hims.patient.ipd.dto.BedViews.BoardEntry;
import com.syncra.hims.patient.ipd.dto.BedViews.WardCensus;
import com.syncra.hims.patient.ipd.dto.DischargeRequest;
import com.syncra.hims.patient.registration.Gender;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Wards, beds and admissions: {@code /api/v1/ipd}. */
@RestController
@RequestMapping("/api/v1/ipd")
public class IpdController {

    private final AdmissionService admissions;
    private final BedService beds;
    private final AuditService audit;

    public IpdController(AdmissionService admissions, BedService beds, AuditService audit) {
        this.admissions = admissions;
        this.beds = beds;
        this.audit = audit;
    }

    /** Occupancy per ward (no patient data). */
    @GetMapping("/wards")
    @PreAuthorize(Roles.IPD_VIEW)
    public List<WardCensus> wards() {
        return beds.census();
    }

    /** Bed board with occupants. */
    @GetMapping("/beds")
    @PreAuthorize(Roles.IPD_VIEW)
    public List<BoardEntry> board(@RequestParam(required = false) String ward,
                                  @RequestParam(required = false) BedStatus status) {
        List<BoardEntry> board = beds.board(ward, status);
        audit.record(Action.READ, "bed_board", ward, null,
                Map.of("occupied", board.stream().filter(b -> b.occupant() != null).count()));
        return board;
    }

    /** Free beds suitable for a patient of the given gender. */
    @GetMapping("/beds/available")
    @PreAuthorize(Roles.IPD_VIEW)
    public List<AvailableBed> available(@RequestParam(required = false) Gender gender,
                                        @RequestParam(required = false) BedCategory category) {
        return beds.available(gender, category);
    }

    @PutMapping("/beds/{bedCode}/status")
    @PreAuthorize(Roles.HOUSEKEEPING)
    public BedState changeBedStatus(@PathVariable String bedCode, @Valid @RequestBody BedStatusRequest request) {
        return beds.changeStatus(bedCode, request.status());
    }

    @PostMapping("/admissions")
    @PreAuthorize(Roles.ADMISSION)
    public ResponseEntity<AdmissionResponse> admit(@Valid @RequestBody AdmitRequest request) {
        AdmissionResponse admission = admissions.admit(request);
        return ResponseEntity.created(URI.create("/api/v1/ipd/admissions/" + admission.admissionId()))
                .body(admission);
    }

    /** Current inpatients. */
    @GetMapping("/admissions")
    @PreAuthorize(Roles.IPD_VIEW)
    public List<AdmissionResponse> active() {
        List<AdmissionResponse> active = admissions.active();
        audit.record(Action.READ, "admission_list", null, null, Map.of("results", active.size()));
        return active;
    }

    @GetMapping("/admissions/{admissionId}")
    @PreAuthorize(Roles.IPD_VIEW)
    public AdmissionResponse get(@PathVariable UUID admissionId) {
        AdmissionResponse admission = admissions.get(admissionId);
        audit.record(Action.READ, "admission", admissionId, null);
        return admission;
    }

    /** Allocate a bed, or transfer to another bed. */
    @PostMapping("/admissions/{admissionId}/bed")
    @PreAuthorize(Roles.INPATIENT_CARE + " or hasRole('RECEPTIONIST')")
    public AdmissionResponse moveBed(@PathVariable UUID admissionId, @Valid @RequestBody BedMoveRequest request) {
        return admissions.moveBed(admissionId, request);
    }

    @PostMapping("/admissions/{admissionId}/discharge-initiation")
    @PreAuthorize(Roles.INPATIENT_CARE)
    public AdmissionResponse initiateDischarge(@PathVariable UUID admissionId) {
        return admissions.initiateDischarge(admissionId);
    }

    @PostMapping("/admissions/{admissionId}/discharge")
    @PreAuthorize(Roles.INPATIENT_CARE)
    public AdmissionResponse discharge(@PathVariable UUID admissionId, @Valid @RequestBody DischargeRequest request) {
        return admissions.discharge(admissionId, request);
    }
}
