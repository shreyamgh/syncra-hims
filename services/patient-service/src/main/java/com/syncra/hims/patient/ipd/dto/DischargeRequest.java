package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.DischargeOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.time.Instant;

/** Close an inpatient stay; {@code dischargedAt} defaults to now and may be back-dated. */
public record DischargeRequest(@NotNull DischargeOutcome outcome, @PastOrPresent Instant dischargedAt) {
}
