package com.syncra.hims.patient.opd.dto;

import com.syncra.hims.patient.opd.QueueStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A department's queue for one day, in board order: in consultation, called,
 * waiting (priority rank then token), on hold, then finished tokens.
 */
public record QueueResponse(
        String departmentCode,
        String departmentName,
        LocalDate date,
        Map<QueueStatus, Long> counts,
        List<VisitResponse> visits) {
}
