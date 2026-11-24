package com.syncra.hims.patient.emr.critical;

import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.critical.CriticalResultRepository.CriticalResult;
import com.syncra.hims.patient.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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
 * GET  /api/v1/emr/critical-results?communicated=&acknowledged=&orderedByMe=&encounterId=   worklist roles
 * POST /api/v1/emr/critical-results/{id}/communicate   lab technician, nurse: who was told, how, read back
 * POST /api/v1/emr/critical-results/{id}/acknowledge   doctor
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emr/critical-results")
public class CriticalResultController {

    public record CommunicateRequest(@NotBlank @Size(max = 150) String communicatedTo, @NotBlank String method,
                                     boolean readBack) {
    }

    public record AcknowledgeRequest(@Size(max = 500) String note) {
    }

    private final CriticalResultService critical;

    public CriticalResultController(CriticalResultService critical) {
        this.critical = critical;
    }

    @GetMapping
    @PreAuthorize(Roles.WORKLIST)
    public List<CriticalResult> list(@RequestParam(required = false) Boolean communicated,
                                     @RequestParam(required = false) Boolean acknowledged,
                                     @RequestParam(defaultValue = "false") boolean orderedByMe,
                                     @RequestParam(required = false) UUID encounterId) {
        return critical.list(communicated, acknowledged, orderedByMe, encounterId);
    }

    @PostMapping("/{id}/communicate")
    @PreAuthorize(Roles.CRITICAL_COMMUNICATION)
    public CriticalResult communicate(@PathVariable UUID id, @Valid @RequestBody CommunicateRequest r) {
        if (!CriticalResultService.METHODS.contains(r.method())) {
            throw ApiException.unprocessable("BAD_METHOD", "method must be PHONE, IN_PERSON or WARD_NURSE");
        }
        return critical.communicate(id, r.communicatedTo(), r.method(), r.readBack());
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(Roles.CONSULTATION)
    public CriticalResult acknowledge(@PathVariable UUID id, @Valid @RequestBody(required = false) AcknowledgeRequest r) {
        return critical.acknowledge(id, r == null ? null : r.note());
    }
}
