package com.syncra.hims.abdm.fhir;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** ABDM health-information types this HIP can produce, with their ABDM codes. */
public enum HiType {

    OP_CONSULTATION("OPConsultation"),
    PRESCRIPTION("Prescription"),
    DISCHARGE_SUMMARY("DischargeSummary"),
    DIAGNOSTIC_REPORT("DiagnosticReport"),
    IMMUNIZATION_RECORD("ImmunizationRecord"),
    HEALTH_DOCUMENT_RECORD("HealthDocumentRecord"),
    WELLNESS_RECORD("WellnessRecord"),
    INVOICE("Invoice");

    private final String code;

    HiType(String code) {
        this.code = code;
    }

    /** The value ABDM uses in {@code hiTypes} and care-context linking (also how the API shows it). */
    @JsonValue
    public String code() {
        return code;
    }

    public static Optional<HiType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }
}
