package com.syncra.hims.patient.emr.dto;

/** Coded values shared with the database CHECK constraints and the AI extraction output. */
public final class ClinicalCodes {

    private ClinicalCodes() {
    }

    /** Where a clinical value came from. DICTATION rows must reference a dictation session. */
    public enum Source { MANUAL, DICTATION, DEVICE }

    public enum DurationUnit { HOURS, DAYS, WEEKS, MONTHS, YEARS }

    public enum Severity { MILD, MODERATE, SEVERE }

    public enum Route {
        ORAL, IV, IM, SC, TOPICAL, INHALATION, NASAL, OPHTHALMIC, OTIC, RECTAL, VAGINAL, SUBLINGUAL, TRANSDERMAL, OTHER
    }

    public enum Frequency { OD, BD, TDS, QID, HS, SOS, STAT, Q4H, Q6H, Q8H, WEEKLY, ALT_DAYS, OTHER }

    public enum Timing { BEFORE_FOOD, AFTER_FOOD, WITH_FOOD, EMPTY_STOMACH, BEDTIME, ANY }
}
