package com.syncra.hims.patient.emr.dto;

import com.syncra.hims.patient.emr.dto.ClinicalCodes.DurationUnit;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Frequency;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Route;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Severity;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Source;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Timing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the doctor signs at the end of a consultation, saved atomically.
 * Rows filled by Shrutlekhan dictation carry {@code source = DICTATION} and the
 * {@code aiSessionId} of the session they came from ({@code dictationSessionRef}).
 */
public record ConsultationRequest(
        @Valid @Size(max = 20) List<DictationSession> dictationSessions,
        @Valid VitalsInput vitals,
        @Valid @Size(max = 30) List<ComplaintInput> complaints,
        @Valid @Size(max = 20) List<DiagnosisInput> diagnoses,
        @Valid PrescriptionInput prescription,
        @Valid @Size(max = 30) List<CdssOverride> cdssOverrides,
        boolean completeVisit) {

    public List<DictationSession> sessions() {
        return dictationSessions == null ? List.of() : dictationSessions;
    }

    public List<ComplaintInput> complaintList() {
        return complaints == null ? List.of() : complaints;
    }

    public List<DiagnosisInput> diagnosisList() {
        return diagnoses == null ? List.of() : diagnoses;
    }

    /** A diagnosis made in this consultation; the ICD-10 code may come from the dictation's suggestion. */
    public record DiagnosisInput(
            @NotBlank @Size(max = 500) String description,
            @Pattern(regexp = "^[A-Za-z][0-9]{2}(\\.[0-9A-Za-z]{1,4})?$", message = "must be an ICD-10 code such as J06.9")
            String icd10Code,
            @Pattern(regexp = "^[0-9]{6,18}$", message = "must be a SNOMED CT concept id") String snomedCode,
            @Pattern(regexp = "^(PROVISIONAL|DIFFERENTIAL|FINAL)$") String type,
            boolean primary,
            Source source,
            @Size(max = 64) String dictationSessionRef) {
    }

    /** The prescriber's reason for going ahead despite a CDSS alert (needed for CONTRAINDICATED and MAJOR). */
    public record CdssOverride(@NotBlank @Size(max = 64) String ruleCode, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    /** Provenance of one dictation run, as reported by the AI service's {@code final} message. */
    public record DictationSession(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String aiSessionId,
            @NotBlank @Pattern(regexp = "^(en-IN|hi-IN)$") String language,
            @NotBlank @Size(max = 40) String engine,
            @NotNull @PositiveOrZero BigDecimal audioSeconds,
            @NotBlank @Size(max = 24) String outcome,
            @NotNull Instant startedAt,
            Instant endedAt) {
    }

    public record ComplaintInput(
            @NotBlank @Size(max = 500) String description,
            @Positive @Max(999) Integer durationValue,
            DurationUnit durationUnit,
            Severity severity,
            boolean chief,
            Source source,
            @Size(max = 64) String dictationSessionRef) {

        @AssertTrue(message = "durationValue and durationUnit go together")
        public boolean isDurationComplete() {
            return (durationValue == null) == (durationUnit == null);
        }
    }

    public record PrescriptionInput(
            @Valid @NotNull @Size(min = 1, max = 30) List<PrescriptionItemInput> items,
            @Size(max = 2000) String advice,
            @FutureOrPresent LocalDate followUpDate) {
    }

    public record PrescriptionItemInput(
            UUID drugId,
            @NotBlank @Size(max = 200) String drugText,
            @Size(max = 40) String dose,
            Route route,
            Frequency frequency,
            Timing timing,
            @Min(1) @Max(365) Integer durationDays,
            @Positive BigDecimal quantity,
            @Size(max = 300) String instructions,
            Source source,
            @Size(max = 64) String dictationSessionRef) {
    }
}
