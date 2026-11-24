package com.syncra.hims.patient.emr.immunization;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vaccination to record.
 *
 * @param administeredAt  when it was given; defaults to now
 * @param primarySource   false for an earlier dose transcribed from a card or certificate
 *                        ({@code reportOrigin} then says which)
 * @param administeredBy  staff id of the vaccinator; defaults to the caller
 */
public record ImmunizationInput(
        @NotBlank @Size(max = 16) String vaccineCode,
        Status status,
        @Size(max = 200) String notDoneReason,
        @Min(1) @Max(10) Integer doseNumber,
        Instant administeredAt,
        Boolean primarySource,
        @Size(max = 100) String reportOrigin,
        @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9./-]+$", message = "letters, digits, '.', '/' or '-'") String lotNumber,
        @Size(max = 100) String manufacturer,
        LocalDate expiryDate,
        Route route,
        Site site,
        @DecimalMin("0.01") @DecimalMax("5.00") BigDecimal doseMl,
        UUID administeredBy,
        LocalDate nextDueDate,
        @Size(max = 500) String notes) {

    public enum Status { COMPLETED, NOT_DONE }

    public enum Route { IM, SC, ORAL, ID, INTRANASAL }

    public enum Site { LEFT_ARM, RIGHT_ARM, LEFT_DELTOID, RIGHT_DELTOID, LEFT_THIGH, RIGHT_THIGH, ORAL, NASAL }

    public Status statusOrDefault() {
        return status == null ? Status.COMPLETED : status;
    }

    public boolean isPrimarySource() {
        return primarySource == null || primarySource;
    }

    @AssertTrue(message = "a dose not given needs notDoneReason, and only then")
    public boolean isReasonConsistent() {
        return (statusOrDefault() == Status.NOT_DONE) == (notDoneReason != null && !notDoneReason.isBlank());
    }

    @AssertTrue(message = "a transcribed dose needs reportOrigin (e.g. 'MCP card'), and only then")
    public boolean isOriginConsistent() {
        return isPrimarySource() == (reportOrigin == null || reportOrigin.isBlank());
    }
}
