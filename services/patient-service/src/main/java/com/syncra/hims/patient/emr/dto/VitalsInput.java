package com.syncra.hims.patient.emr.dto;

import com.syncra.hims.patient.emr.dto.ClinicalCodes.Source;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** A set of vital signs; ranges mirror the clinical.vital_sign CHECK constraints. */
public record VitalsInput(
        @DecimalMin("25") @DecimalMax("45") BigDecimal temperatureC,
        @Min(20) @Max(250) Integer pulseBpm,
        @Min(4) @Max(80) Integer respiratoryRate,
        @Min(40) @Max(300) Integer bpSystolic,
        @Min(20) @Max(200) Integer bpDiastolic,
        @Min(30) @Max(100) Integer spo2Percent,
        @DecimalMin("0.3") @DecimalMax("400") BigDecimal weightKg,
        @DecimalMin("20") @DecimalMax("250") BigDecimal heightCm,
        @Min(10) @Max(1500) Integer bloodGlucoseMgDl,
        @Min(0) @Max(10) Integer painScore,
        Source source,
        @Size(max = 64) String dictationSessionRef) {

    @AssertTrue(message = "systolic pressure must be higher than diastolic")
    public boolean isBloodPressureConsistent() {
        return bpSystolic == null || bpDiastolic == null || bpSystolic > bpDiastolic;
    }

    public boolean hasAnyValue() {
        return temperatureC != null || pulseBpm != null || respiratoryRate != null || bpSystolic != null
                || bpDiastolic != null || spo2Percent != null || weightKg != null || heightCm != null
                || bloodGlucoseMgDl != null || painScore != null;
    }

    public Source sourceOrManual() {
        return source == null ? Source.MANUAL : source;
    }
}
