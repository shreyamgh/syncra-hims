package com.syncra.hims.abdm.fhir;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the FHIR documents for one encounter are made from, read in one go from the
 * clinical schema. Rows entered in error, unsigned notes, draft or cancelled prescriptions and
 * cancelled orders are already excluded.
 */
public record EncounterRecord(
        Encounter encounter,
        Patient patient,
        Admission admission,
        List<Vitals> vitals,
        List<Complaint> complaints,
        List<Diagnosis> diagnoses,
        List<Allergy> allergies,
        List<Prescription> prescriptions,
        List<Order> orders,
        List<Study> studies,
        List<Immunization> immunizations,
        List<Note> notes,
        Map<UUID, Practitioner> practitioners,
        List<Invoice> invoices) {

    /** An encounter with no issued bills. */
    public EncounterRecord(Encounter encounter, Patient patient, Admission admission, List<Vitals> vitals,
                           List<Complaint> complaints, List<Diagnosis> diagnoses, List<Allergy> allergies,
                           List<Prescription> prescriptions, List<Order> orders, List<Study> studies,
                           List<Immunization> immunizations, List<Note> notes, Map<UUID, Practitioner> practitioners) {
        this(encounter, patient, admission, vitals, complaints, diagnoses, allergies, prescriptions, orders, studies,
                immunizations, notes, practitioners, List.of());
    }

    public record Encounter(UUID id, UUID patientId, String type, String status, Instant startedAt, Instant endedAt,
                            String departmentCode, String departmentName, UUID attendingDoctorId) {
    }

    public record Patient(UUID id, String uhid, String firstName, String middleName, String lastName, String fullName,
                          String gender, LocalDate dateOfBirth, String mobile, String abhaNumber, String abhaAddress,
                          boolean abhaVerified, String addressLine, String cityVillage, String district, String state,
                          String pincode) {
    }

    public record Admission(UUID id, String admissionNumber, String status, Instant admittedAt, Instant dischargedAt,
                            UUID admittingDoctorId, String provisionalDiagnosis) {
    }

    public record Practitioner(UUID id, String fullName, String title, String qualification,
                               String councilRegistration, String hprId) {
    }

    public record Vitals(UUID id, Instant recordedAt, BigDecimal temperatureC, Integer pulseBpm, Integer respiratoryRate,
                         Integer bpSystolic, Integer bpDiastolic, Integer spo2Percent, BigDecimal weightKg,
                         BigDecimal heightCm, BigDecimal bmi, Integer bloodGlucoseMgDl, Integer painScore) {
    }

    public record Complaint(UUID id, String description, Integer durationValue, String durationUnit, String severity,
                            String snomedCode, boolean chief, Instant recordedAt) {
    }

    public record Diagnosis(UUID id, String icd10Code, String snomedCode, String description, String type,
                            boolean primary, Instant recordedAt) {
    }

    public record Allergy(UUID id, String allergenType, String allergenText, String reaction, String severity,
                          Instant recordedAt) {
    }

    public record Prescription(UUID id, UUID prescribedBy, Instant issuedAt, String advice, LocalDate followUpDate,
                               List<PrescriptionItem> items) {
    }

    public record PrescriptionItem(UUID id, int lineNo, String drugText, String dose, String route, String frequency,
                                   String timing, Integer durationDays, BigDecimal quantity, String instructions) {
    }

    /**
     * An investigation ordered in this encounter.
     *
     * @param loincCode the test's LOINC code (laboratory tests), with {@code loincDisplay}
     * @param values    the measured values as reported, in report order
     */
    public record Order(UUID id, String serviceCode, String serviceName, String orderType, String status,
                        String priority, String clinicalIndication, UUID orderedBy, Instant orderedAt,
                        Instant completedAt, String resultSummary, String loincCode, String loincDisplay,
                        Instant sampleCollectedAt, List<ResultValue> values) {

        /** An order without coded values (imaging, or a result given as a summary only). */
        public Order(UUID id, String serviceCode, String serviceName, String orderType, String status, String priority,
                     String clinicalIndication, UUID orderedBy, Instant orderedAt, Instant completedAt,
                     String resultSummary) {
            this(id, serviceCode, serviceName, orderType, status, priority, clinicalIndication, orderedBy, orderedAt,
                    completedAt, resultSummary, null, null, null, List.of());
        }

        public boolean isReported() {
            return ("REPORTED".equals(status) || "COMPLETED".equals(status))
                    && resultSummary != null && !resultSummary.isBlank();
        }
    }

    /**
     * One reported value of a lab test, as the laboratory signed it out.
     *
     * @param flag           N normal, L low, H high, A abnormal, C critical (set by the server from the range)
     * @param referenceRange the range the value was flagged against, e.g. "3.5-5.1", "< 40"
     * @param loincCode      the parameter's LOINC code, when the parameter is in the test's template
     */
    public record ResultValue(String code, String name, BigDecimal value, String text, String unit,
                              String referenceRange, String flag, String loincCode, String loincDisplay) {
    }

    /**
     * An issued (final) bill of this encounter: OPD, IPD, pharmacy or emergency. Draft, interim
     * and cancelled bills are left out.
     */
    public record Invoice(UUID id, String number, String billType, Instant issuedAt, BigDecimal gross,
                          BigDecimal discount, BigDecimal tax, BigDecimal net, BigDecimal roundOff, BigDecimal payable,
                          List<InvoiceLine> lines) {
    }

    /**
     * A bill line. {@code mrp} (per unit), {@code batchNumber} and {@code expiryDate} are set for
     * medicines sold by the pharmacy, with {@code unit} the dispensing unit.
     */
    public record InvoiceLine(UUID id, int sequence, String serviceCode, String serviceName, String category,
                              String description, LocalDate serviceDate, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, BigDecimal gross, BigDecimal discount, BigDecimal gstRate,
                              BigDecimal tax, BigDecimal lineTotal, BigDecimal mrp, String batchNumber,
                              LocalDate expiryDate, String medicineName) {

        public boolean isMedicine() {
            return mrp != null;
        }
    }

    public record Note(UUID id, String noteType, String content, UUID authorId, Instant signedAt) {
    }

    /**
     * An imaging study from the PACS index, ordered in this encounter.
     *
     * @param reportText the signed radiology report, when the study is reported (FINAL or ADDENDUM)
     */
    public record Study(UUID id, UUID orderId, String studyInstanceUid, String accessionNumber, String modality,
                        LocalDate studyDate, String description, Integer seriesCount, Integer instanceCount,
                        String reportStatus, String reportText, UUID reportedBy, Instant reportedAt) {

        public boolean isReported() {
            return ("FINAL".equals(reportStatus) || "ADDENDUM".equals(reportStatus)) && reportText != null
                    && reportedAt != null;
        }
    }

    /**
     * A vaccination given in this encounter (or transcribed from the patient's card when
     * {@code primarySource} is false). Entries marked in error are already excluded.
     */
    public record Immunization(UUID id, String vaccineCode, String vaccineName, String targetDisease,
                               Integer seriesDoses, String status, String notDoneReason, Integer doseNumber,
                               Instant administeredAt, boolean primarySource, String reportOrigin, String lotNumber,
                               String manufacturer, LocalDate expiryDate, String route, String site, BigDecimal doseMl,
                               UUID administeredBy, LocalDate nextDueDate, String notes, Instant recordedAt) {

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }

    /** A finished investigation: the order, its imaging study when there is one, and the result. */
    public record Report(Order order, Study study) {

        public Instant issuedAt() {
            if (study != null && study.isReported()) {
                return study.reportedAt();
            }
            return order.completedAt() != null ? order.completedAt() : order.orderedAt();
        }

        public boolean isImaging() {
            return "RADIOLOGY".equals(order.orderType());
        }
    }

    /**
     * Investigations with a result to share: a reported study (radiology), or an order completed or
     * reported with a result summary. Draft reports and pending orders are left out.
     */
    public List<Report> reports() {
        List<Report> reports = new java.util.ArrayList<>();
        for (Order order : orders) {
            Study study = studies.stream().filter(s -> s.orderId().equals(order.id()) && s.isReported())
                    .findFirst().orElse(null);
            if (study != null || order.isReported()) {
                reports.add(new Report(order, study));
            }
        }
        return reports;
    }

    public Optional<Practitioner> practitioner(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(practitioners.get(id));
    }

    public boolean isInpatient() {
        return "IPD".equals(encounter.type());
    }

    /** True once the stay is over: discharged, LAMA, expired, absconded or transferred out. */
    public boolean isDischarged() {
        return admission != null && admission.dischargedAt() != null;
    }

    public List<Note> notes(String type) {
        return notes.stream().filter(n -> n.noteType().equals(type)).toList();
    }
}
