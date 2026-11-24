package com.syncra.hims.patient.emr.immunization;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.patient.emr.immunization.ImmunizationRepository.Immunization;
import com.syncra.hims.patient.emr.immunization.ImmunizationRepository.Vaccine;
import com.syncra.hims.patient.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vaccinations.
 *
 * <pre>
 * GET  /api/v1/emr/vaccines                                              catalogue (pick-list)
 * POST /api/v1/emr/encounters/{id}/immunizations                         record a dose given (or transcribed)
 * POST /api/v1/emr/encounters/{id}/immunizations/{immunizationId}/entered-in-error
 * GET  /api/v1/emr/patients/{uhid}/immunizations                         vaccination history, with next due dates
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emr")
public class ImmunizationController {

    public record ErrorRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record History(String uhid, List<Immunization> immunizations) {
    }

    private final ImmunizationService immunizations;
    private final AuditService audit;

    public ImmunizationController(ImmunizationService immunizations, AuditService audit) {
        this.immunizations = immunizations;
        this.audit = audit;
    }

    @GetMapping("/vaccines")
    @PreAuthorize(Roles.CLINICAL)
    public List<Vaccine> vaccines() {
        return immunizations.vaccines();
    }

    @PostMapping("/encounters/{encounterId}/immunizations")
    @PreAuthorize(Roles.CLINICAL)
    @ResponseStatus(HttpStatus.CREATED)
    public Immunization record(@PathVariable UUID encounterId, @Valid @RequestBody ImmunizationInput input) {
        return immunizations.record(encounterId, input);
    }

    @PostMapping("/encounters/{encounterId}/immunizations/{immunizationId}/entered-in-error")
    @PreAuthorize(Roles.CLINICAL)
    public Immunization enteredInError(@PathVariable UUID encounterId, @PathVariable UUID immunizationId,
                                       @Valid @RequestBody ErrorRequest request) {
        return immunizations.markEnteredInError(encounterId, immunizationId, request.reason());
    }

    @GetMapping("/patients/{uhid}/immunizations")
    @PreAuthorize(Roles.CLINICAL)
    public History history(@PathVariable String uhid) {
        Map.Entry<UUID, List<Immunization>> history = immunizations.history(uhid);
        audit.record(Action.READ, "immunization_history", uhid, history.getKey());
        return new History(uhid.trim().toUpperCase(), history.getValue());
    }
}
