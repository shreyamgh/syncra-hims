package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.AssignmentReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Allocate a bed to an admission, or move it to another bed. */
public record BedMoveRequest(@NotBlank @Size(max = 12) String bedCode, AssignmentReason reason) {
}
