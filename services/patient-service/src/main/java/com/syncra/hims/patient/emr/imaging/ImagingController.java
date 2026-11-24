package com.syncra.hims.patient.emr.imaging;

import com.syncra.hims.patient.emr.imaging.ImagingRepository.Study;
import com.syncra.hims.patient.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET  /api/v1/emr/imaging/studies?reportStatus=UNREPORTED&amp;matched=true   radiology worklist
 * POST /api/v1/emr/imaging/studies/{id}/match   {orderId}                    radiographer, radiologist
 * POST /api/v1/emr/imaging/studies/{id}/report  {content, impression}        radiologist (again = addendum)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emr/imaging/studies")
public class ImagingController {

    public record MatchRequest(@NotNull UUID orderId) {
    }

    public record ReportRequest(@NotBlank @Size(max = 20_000) String content, @Size(max = 500) String impression) {
    }

    private final ImagingService imaging;

    public ImagingController(ImagingService imaging) {
        this.imaging = imaging;
    }

    @GetMapping
    @PreAuthorize(Roles.WORKLIST)
    public List<Study> worklist(@RequestParam(required = false) List<String> reportStatus,
                                @RequestParam(required = false) Boolean matched,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                Instant reportedSince) {
        return imaging.worklist(reportStatus == null ? List.of() : reportStatus, matched, reportedSince);
    }

    @PostMapping("/{studyId}/match")
    @PreAuthorize(Roles.IMAGING_DESK)
    public Study match(@PathVariable UUID studyId, @Valid @RequestBody MatchRequest r) {
        return imaging.match(studyId, r.orderId());
    }

    @PostMapping("/{studyId}/report")
    @PreAuthorize(Roles.RADIOLOGY_REPORTING)
    public Study report(@PathVariable UUID studyId, @Valid @RequestBody ReportRequest r) {
        return imaging.report(studyId, r.content(), r.impression());
    }
}
