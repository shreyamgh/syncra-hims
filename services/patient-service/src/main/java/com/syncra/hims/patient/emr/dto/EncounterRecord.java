package com.syncra.hims.patient.emr.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The clinical record of one encounter as shown on the Doctor Desk. */
public record EncounterRecord(
        UUID encounterId,
        String encounterType,
        String status,
        Patient patient,
        String departmentCode,
        String departmentName,
        Visit visit,
        Vitals latestVitals,
        List<Vitals> vitalsHistory,
        List<Complaint> complaints,
        List<Prescription> prescriptions,
        List<Allergy> allergies,
        List<com.syncra.hims.patient.emr.immunization.ImmunizationRepository.Immunization> immunizations,
        List<com.syncra.hims.patient.emr.entries.EntriesRepository.Diagnosis> diagnoses,
        List<com.syncra.hims.patient.emr.entries.EntriesRepository.Note> notes,
        List<com.syncra.hims.patient.emr.orders.OrderRepository.Order> orders,
        List<CdssAlert> cdssAlerts,
        com.syncra.hims.patient.emr.orders.OrderRepository.Creatinine latestCreatinine,
        List<com.syncra.hims.patient.emr.critical.CriticalResultRepository.CriticalResult> criticalResults) {

    /** A CDSS alert raised when the prescription was signed, and how the prescriber resolved it. */
    public record CdssAlert(String alertType, String severity, String ruleCode, String message, String resolution,
                            String overrideReason, String kbVersion, Instant raisedAt) {
    }

    public record Patient(UUID id, String uhid, String name, String gender, Integer ageYears) {
    }

    public record Visit(UUID visitId, int tokenNumber, String queueStatus) {
    }

    public record Vitals(UUID id, Instant recordedAt, String recordedBy, BigDecimal temperatureC, Integer pulseBpm,
                         Integer respiratoryRate, Integer bpSystolic, Integer bpDiastolic, Integer spo2Percent,
                         BigDecimal weightKg, BigDecimal heightCm, BigDecimal bmi, Integer bloodGlucoseMgDl,
                         Integer painScore, String source) {
    }

    public record Complaint(UUID id, String description, Integer durationValue, String durationUnit,
                            String severity, boolean chief, String source) {
    }

    public record Prescription(UUID id, String status, Instant issuedAt, String prescribedBy,
                               String prescriberRegistration, String advice, LocalDate followUpDate,
                               List<PrescriptionItem> items) {
    }

    public record PrescriptionItem(int lineNo, String drugText, String dose, String route, String frequency,
                                   String timing, Integer durationDays, BigDecimal quantity, String instructions,
                                   String source) {
    }

    public record Allergy(String allergen, String reaction, String severity, String drugClass) {
    }
}
