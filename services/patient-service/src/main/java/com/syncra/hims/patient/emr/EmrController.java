package com.syncra.hims.patient.emr;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.patient.security.Roles;
import com.syncra.hims.patient.emr.dto.ConsultationRequest;
import com.syncra.hims.patient.emr.dto.EncounterRecord;
import com.syncra.hims.patient.emr.dto.VitalsInput;
import jakarta.validation.Valid;
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

/** Clinical record of an encounter: {@code /api/v1/emr/encounters/{id}}. */
@RestController
@RequestMapping("/api/v1/emr/encounters/{encounterId}")
public class EmrController {

    private final EmrService emr;
    private final AuditService audit;

    public EmrController(EmrService emr, AuditService audit) {
        this.emr = emr;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize(Roles.CLINICAL)
    public EncounterRecord get(@PathVariable UUID encounterId) {
        EncounterRecord record = emr.record(encounterId);
        audit.record(Action.READ, "encounter_record", encounterId, record.patient().id());
        return record;
    }

    @PostMapping("/vitals")
    @PreAuthorize(Roles.CLINICAL)
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterRecord recordVitals(@PathVariable UUID encounterId, @Valid @RequestBody VitalsInput vitals) {
        return emr.recordVitals(encounterId, vitals);
    }

    /** Signs the consultation: saves vitals, complaints and an issued prescription atomically. */
    @PostMapping("/consultation")
    @PreAuthorize(Roles.CONSULTATION)
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterRecord recordConsultation(@PathVariable UUID encounterId,
                                              @Valid @RequestBody ConsultationRequest request) {
        return emr.recordConsultation(encounterId, request);
    }
}
