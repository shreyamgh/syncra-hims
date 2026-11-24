package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.AdmissionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Admit a patient. {@code bedCode} is optional (a bed can be allocated later);
 * {@code tariffPlanCode} defaults to the hospital's default plan.
 */
public record AdmitRequest(
        @NotBlank String uhid,
        @NotBlank @Size(max = 8) String departmentCode,
        @NotNull UUID admittingDoctorId,
        @NotNull AdmissionType admissionType,
        @Size(max = 12) String bedCode,
        @Size(max = 16) String tariffPlanCode,
        @Size(max = 500) String provisionalDiagnosis,
        @FutureOrPresent LocalDate expectedDischargeDate,
        @Valid Attendant attendant,
        @Valid Payer payer,
        Boolean medicoLegal) {

    public record Attendant(
            @Size(max = 150) String name,
            @Size(max = 30) String relation,
            @Pattern(regexp = "^[6-9]\\d{9}$") String mobile) {
    }

    public record Payer(
            @Size(max = 150) String insurerOrScheme,
            @Size(max = 50) String policyNumber,
            @Size(max = 50) String preauthNumber,
            @PositiveOrZero BigDecimal preauthAmount) {
    }
}
