package com.syncra.hims.patient.opd.dto;

import com.syncra.hims.patient.opd.VisitPriority;
import com.syncra.hims.patient.opd.VisitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Issue an OPD token. {@code priority} defaults from the visit type and the
 * patient's age (60+ senior citizen, under 5) when omitted.
 */
public record RegisterVisitRequest(
        @NotBlank String uhid,
        @NotBlank @Size(max = 8) String departmentCode,
        UUID doctorId,
        VisitType visitType,
        VisitPriority priority,
        @Size(max = 150) String referredBy,
        UUID followUpOfVisitId,
        Boolean medicoLegal) {

    public VisitType typeOrDefault() {
        return visitType == null ? VisitType.NEW : visitType;
    }
}
