package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.BedStatus;
import jakarta.validation.constraints.NotNull;

/** Housekeeping change: AVAILABLE, RESERVED, MAINTENANCE or BLOCKED. */
public record BedStatusRequest(@NotNull BedStatus status) {
}
