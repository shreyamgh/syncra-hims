package com.syncra.hims.abdm.fhir;

import static com.syncra.hims.abdm.fhir.FhirDocument.concept;

import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.fhir.EncounterRecord.Allergy;
import com.syncra.hims.abdm.fhir.EncounterRecord.Complaint;
import com.syncra.hims.abdm.fhir.EncounterRecord.Diagnosis;
import com.syncra.hims.abdm.fhir.EncounterRecord.Invoice;
import com.syncra.hims.abdm.fhir.EncounterRecord.InvoiceLine;
import com.syncra.hims.abdm.fhir.EncounterRecord.Note;
import com.syncra.hims.abdm.fhir.EncounterRecord.Order;
import com.syncra.hims.abdm.fhir.EncounterRecord.Prescription;
import com.syncra.hims.abdm.fhir.EncounterRecord.PrescriptionItem;
import com.syncra.hims.abdm.fhir.EncounterRecord.ResultValue;
import com.syncra.hims.abdm.fhir.EncounterRecord.Vitals;
import com.syncra.hims.common.config.HospitalTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.ChargeItem;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Timing;
import org.springframework.stereotype.Component;

/**
 * Builds the ABDM health records of an encounter as FHIR R4 document Bundles, following the
 * NRCES implementation guide's structure (Composition types and section codes):
 *
 * <ul>
 *   <li>{@link HiType#OP_CONSULTATION} — OPConsultRecord: chief complaints, examination (vitals),
 *       allergies, investigation advice, medications, follow-up, signed notes.</li>
 *   <li>{@link HiType#PRESCRIPTION} — PrescriptionRecord: issued prescriptions.</li>
 *   <li>{@link HiType#DISCHARGE_SUMMARY} — DischargeSummaryRecord, once the stay has ended:
 *       complaints, examination, allergies, investigations with results, discharge medications,
 *       care plan, and the signed discharge-summary note.</li>
 *   <li>{@link HiType#DIAGNOSTIC_REPORT} — DiagnosticReportRecord: lab reports with one coded
 *       Observation per reported value (LOINC, UCUM, reference range, interpretation), and
 *       imaging reports with their ImagingStudy.</li>
 *   <li>{@link HiType#IMMUNIZATION_RECORD} — ImmunizationRecord: doses given, transcribed or
 *       deferred, and the next doses due.</li>
 *   <li>{@link HiType#WELLNESS_RECORD} — WellnessRecord: the encounter's vital signs, body
 *       measurements and general assessment (blood glucose, pain score), every reading.</li>
 *   <li>{@link HiType#HEALTH_DOCUMENT_RECORD} — HealthDocumentRecord: an inpatient stay's signed
 *       case notes (progress, procedure, nursing…), which no other record carries.</li>
 *   <li>{@link HiType#INVOICE} — InvoiceRecord: the encounter's final bills, one Invoice each, with
 *       a ChargeItem per line, price components (rate, MRP, discount, CGST, SGST) and, for
 *       medicines, the batch and expiry sold.</li>
 * </ul>
 * Diagnoses are Conditions referenced from {@code Encounter.diagnosis} (and as the reason for
 * each medication). Terminology is SNOMED CT, LOINC, UCUM and ICD-10 where the source row holds
 * a code; free text otherwise.
 */
@Component
public class HealthRecordBuilder {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final AbdmProperties properties;
    private final HospitalTime time;

    public HealthRecordBuilder(AbdmProperties properties, HospitalTime time) {
        this.properties = properties;
        this.time = time;
    }

    /** The record types this encounter has content for. */
    public Set<HiType> available(EncounterRecord record) {
        Set<HiType> types = EnumSet.noneOf(HiType.class);
        if ("CANCELLED".equals(record.encounter().status())) {
            return types;
        }
        if (record.isInpatient()) {
            if (record.isDischarged()) {
                types.add(HiType.DISCHARGE_SUMMARY);
            }
        } else if (!record.complaints().isEmpty() || !record.vitals().isEmpty() || !record.diagnoses().isEmpty()
                || !record.prescriptions().isEmpty() || !record.notes().isEmpty()) {
            types.add(HiType.OP_CONSULTATION);
        }
        if (!record.prescriptions().isEmpty()) {
            types.add(HiType.PRESCRIPTION);
        }
        if (!record.reports().isEmpty()) {
            types.add(HiType.DIAGNOSTIC_REPORT);
        }
        if (!record.immunizations().isEmpty()) {
            types.add(HiType.IMMUNIZATION_RECORD);
        }
        if (!caseNotes(record).isEmpty()) {
            types.add(HiType.HEALTH_DOCUMENT_RECORD);
        }
        if (!record.vitals().isEmpty()) {
            types.add(HiType.WELLNESS_RECORD);
        }
        if (!record.invoices().isEmpty()) {
            types.add(HiType.INVOICE);
        }
        return types;
    }

    /** The document of the given type, or empty when the encounter has no such content. */
    public Optional<Bundle> build(HiType type, EncounterRecord record) {
        if (!available(record).contains(type)) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case OP_CONSULTATION -> opConsultation(record);
            case PRESCRIPTION -> prescription(record);
            case DISCHARGE_SUMMARY -> dischargeSummary(record);
            case DIAGNOSTIC_REPORT -> diagnosticReport(record);
            case IMMUNIZATION_RECORD -> immunizationRecord(record);
            case HEALTH_DOCUMENT_RECORD -> healthDocumentRecord(record);
            case WELLNESS_RECORD -> wellnessRecord(record);
            case INVOICE -> invoiceRecord(record);
        });
    }

    // ------------------------------------------------------------------ documents

    private Bundle opConsultation(EncounterRecord record) {
        Context c = context(record, HiType.OP_CONSULTATION, "371530004", "Clinical consultation report",
                Codes.NRCES + "OPConsultRecord", record.practitioner(record.encounter().attendingDoctorId())
                        .or(() -> record.prescriptions().stream().findFirst()
                                .flatMap(p -> record.practitioner(p.prescribedBy()))));
        String title = "OP Consultation";
        c.doc.composition().setTitle(title);
        c.doc.narrative(title, record.encounter().departmentName() + " — " + displayDate(record.encounter().startedAt()));

        section(c, "Chief complaints", "422843007", "Chief complaint section", complaints(c, record));
        section(c, "Physical examination", "425044008", "Physical exam section", vitals(c, record.vitals()));
        section(c, "Allergies", "722446000", "Allergy record", allergies(c, record));
        section(c, "Investigation advice", "721963009", "Order document", serviceRequests(c, record));
        section(c, "Medications", "721912009", "Medication summary document", medications(c, record));
        section(c, "Follow up", "736271009", "Outpatient care plan", followUp(c, record));
        // Radiology reports travel in the DiagnosticReport record, discharge summaries in theirs.
        List<Note> notes = record.notes().stream()
                .filter(n -> !"RADIOLOGY_REPORT".equals(n.noteType()) && !"DISCHARGE_SUMMARY".equals(n.noteType()))
                .toList();
        section(c, "Document reference", "371530004", "Clinical consultation report",
                documents(c, notes, "371530004", "Clinical consultation report"));
        return c.doc.bundle();
    }

    private Bundle prescription(EncounterRecord record) {
        Context c = context(record, HiType.PRESCRIPTION, "440545006", "Prescription record",
                Codes.NRCES + "PrescriptionRecord",
                record.prescriptions().stream().findFirst().flatMap(p -> record.practitioner(p.prescribedBy())));
        c.doc.composition().setTitle("Prescription");
        Instant issued = record.prescriptions().get(record.prescriptions().size() - 1).issuedAt();
        c.doc.composition().setDateElement(c.doc.dateTime(issued));
        c.doc.narrative("Prescription", record.encounter().departmentName() + " — " + displayDate(issued));
        section(c, "Prescription", "440545006", "Prescription record", medications(c, record));
        return c.doc.bundle();
    }

    private Bundle dischargeSummary(EncounterRecord record) {
        List<Note> summaries = record.notes("DISCHARGE_SUMMARY");
        Optional<EncounterRecord.Practitioner> author = summaries.isEmpty()
                ? record.practitioner(record.encounter().attendingDoctorId())
                        .or(() -> record.practitioner(record.admission().admittingDoctorId()))
                : record.practitioner(summaries.get(summaries.size() - 1).authorId());
        Context c = context(record, HiType.DISCHARGE_SUMMARY, "373942005", "Discharge summary",
                Codes.NRCES + "DischargeSummaryRecord", author);
        c.doc.composition().setTitle("Discharge Summary");
        c.doc.composition().setDateElement(c.doc.dateTime(record.admission().dischargedAt()));
        c.doc.narrative("Discharge Summary", record.encounter().departmentName() + " — admitted "
                + displayDate(record.admission().admittedAt()) + ", discharged "
                + displayDate(record.admission().dischargedAt()) + " (" + record.admission().admissionNumber() + ")");

        List<Vitals> vitals = record.vitals();
        List<Vitals> admissionAndDischarge = vitals.size() <= 2 ? vitals : List.of(vitals.get(0), vitals.get(vitals.size() - 1));
        section(c, "Chief complaints", "422843007", "Chief complaint section", complaints(c, record));
        section(c, "Physical examination", "425044008", "Physical exam section", vitals(c, admissionAndDischarge));
        section(c, "Allergies", "722446000", "Allergy record", allergies(c, record));
        section(c, "Investigations", "721981007", "Diagnostic studies report",
                diagnosticReports(c, record, record.reports()));
        section(c, "Medications", "1003606003", "Medication history section", medications(c, record));
        section(c, "Care plan", "734163000", "Care plan", followUp(c, record));
        section(c, "Document reference", "373942005", "Discharge summary",
                documents(c, summaries, "373942005", "Discharge summary"));
        return c.doc.bundle();
    }

    private Bundle diagnosticReport(EncounterRecord record) {
        List<EncounterRecord.Report> reports = record.reports();
        EncounterRecord.Report latest = reports.stream()
                .max(Comparator.comparing(EncounterRecord.Report::issuedAt)).orElseThrow();
        Optional<EncounterRecord.Practitioner> interpreter = reports.stream()
                .filter(r -> r.study() != null && r.study().reportedBy() != null)
                .max(Comparator.comparing(EncounterRecord.Report::issuedAt))
                .flatMap(r -> record.practitioner(r.study().reportedBy()));
        Context c = context(record, HiType.DIAGNOSTIC_REPORT, "721981007", "Diagnostic studies report",
                Codes.NRCES + "DiagnosticReportRecord", interpreter);
        c.doc.composition().setTitle("Diagnostic Report");
        c.doc.composition().setDateElement(c.doc.dateTime(latest.issuedAt()));
        c.doc.narrative("Diagnostic Report", reports.stream().map(r -> r.order().serviceName())
                .collect(java.util.stream.Collectors.joining(", ")) + " — " + displayDate(latest.issuedAt()));
        section(c, "Laboratory reports", "721981007", "Diagnostic studies report",
                diagnosticReports(c, record, reports.stream().filter(r -> !r.isImaging()).toList()));
        section(c, "Imaging reports", "721981007", "Diagnostic studies report",
                diagnosticReports(c, record, reports.stream().filter(EncounterRecord.Report::isImaging).toList()));
        return c.doc.bundle();
    }

    private Bundle immunizationRecord(EncounterRecord record) {
        List<EncounterRecord.Immunization> given = record.immunizations();
        EncounterRecord.Immunization latest = given.get(given.size() - 1);
        Optional<EncounterRecord.Practitioner> vaccinator = given.stream()
                .filter(i -> i.administeredBy() != null).reduce((a, b) -> b)
                .flatMap(i -> record.practitioner(i.administeredBy()));
        Context c = context(record, HiType.IMMUNIZATION_RECORD, "41000179103", "Immunization record",
                Codes.NRCES + "ImmunizationRecord", vaccinator);
        c.doc.composition().setTitle("Immunization Record");
        c.doc.composition().setDateElement(c.doc.dateTime(latest.recordedAt()));
        c.doc.narrative("Immunization Record", given.stream().map(EncounterRecord.Immunization::vaccineName)
                .collect(java.util.stream.Collectors.joining(", ")) + " — " + displayDate(latest.administeredAt()));
        List<Reference> entries = new ArrayList<>();
        Map<UUID, Reference> byId = new LinkedHashMap<>();
        for (EncounterRecord.Immunization i : given) {
            Reference ref = c.doc.add(immunization(c, record, i));
            byId.put(i.id(), ref);
            entries.add(ref);
        }
        recommendation(c, given, byId).ifPresent(entries::add);
        section(c, "Immunizations", "41000179103", "Immunization record", entries);
        return c.doc.bundle();
    }

    private Bundle wellnessRecord(EncounterRecord record) {
        List<Vitals> readings = record.vitals();
        Instant latest = readings.get(readings.size() - 1).recordedAt();
        Context c = context(record, HiType.WELLNESS_RECORD, new CodeableConcept().setText("Wellness Record"),
                Codes.NRCES + "WellnessRecord", Optional.empty());
        c.doc.composition().setTitle("Wellness Record");
        c.doc.composition().setDateElement(c.doc.dateTime(latest));
        c.doc.narrative("Wellness Record", readings.size() + (readings.size() == 1 ? " reading" : " readings")
                + " of vital signs and body measurements, " + record.encounter().departmentName() + " — "
                + displayDate(readings.get(0).recordedAt())
                + (displayDate(latest).equals(displayDate(readings.get(0).recordedAt())) ? "" : " to " + displayDate(latest)));
        Map<Measure, List<Reference>> sections = new java.util.EnumMap<>(Measure.class);
        for (Measured m : measurements(c, readings)) {
            m.observation().setMeta(new Meta().addProfile(Codes.NRCES + m.kind().profile));
            sections.computeIfAbsent(m.kind(), k -> new ArrayList<>()).add(c.doc.add(m.observation()));
        }
        sections.forEach((kind, refs) -> refs.forEach(c.doc.section(kind.title)::addEntry));
        return c.doc.bundle();
    }

    private Bundle healthDocumentRecord(EncounterRecord record) {
        List<Note> notes = caseNotes(record);
        Note latest = notes.stream().max(Comparator.comparing(Note::signedAt)).orElseThrow();
        Context c = context(record, HiType.HEALTH_DOCUMENT_RECORD, concept(Codes.SNOMED, "419891008", "Record artifact"),
                Codes.NRCES + "HealthDocumentRecord", record.practitioner(latest.authorId()));
        c.doc.composition().setTitle("Health Document");
        c.doc.composition().setDateElement(c.doc.dateTime(latest.signedAt()));
        c.doc.narrative("Health Document", "Case notes, " + record.encounter().departmentName()
                + (record.admission() != null ? " (" + record.admission().admissionNumber() + ")" : "") + " — "
                + notes.stream().map(n -> noteTitle(n.noteType())).distinct().collect(Collectors.joining(", ")));
        Composition.SectionComponent section = c.doc.section("Health Document");
        documents(c, notes, "419891008", "Record artifact").forEach(section::addEntry);
        return c.doc.bundle();
    }

    private Bundle invoiceRecord(EncounterRecord record) {
        List<Invoice> invoices = record.invoices();
        Invoice latest = invoices.stream().max(Comparator.comparing(Invoice::issuedAt)).orElseThrow();
        Context c = context(record, HiType.INVOICE, new CodeableConcept().setText("Invoice Record"),
                Codes.NRCES + "InvoiceRecord", Optional.empty());
        String title = invoices.stream().map(i -> billKind(i.billType())).distinct().count() == 1
                ? billKind(latest.billType()) + " Invoice Record" : "Invoice Record";
        c.doc.composition().setTitle(title);
        c.doc.composition().setDateElement(c.doc.dateTime(latest.issuedAt()));
        c.doc.narrative(title, invoices.stream().map(i -> i.number() + ": ₹" + plain(i.payable()))
                .collect(Collectors.joining(", ")) + " — " + displayDate(latest.issuedAt()));
        for (Invoice invoice : invoices) {
            c.doc.section(billKind(invoice.billType()) + " Invoice Details").addEntry(c.doc.add(invoice(c, invoice)));
        }
        return c.doc.bundle();
    }

    // ------------------------------------------------------------------ document skeleton

    /** Per-document state: the bundle and references to the shared resources. */
    private record Context(EncounterRecord record, FhirDocument doc, Reference patient, Reference encounter,
                           Encounter encounterResource, Reference author, Reference organization,
                           Map<UUID, Reference> diagnoses) {
    }

    private Context context(EncounterRecord record, HiType type, String typeCode, String typeDisplay, String profile,
                            Optional<EncounterRecord.Practitioner> author) {
        return context(record, type, concept(Codes.SNOMED, typeCode, typeDisplay), profile, author);
    }

    private Context context(EncounterRecord record, HiType type, CodeableConcept compositionType, String profile,
                            Optional<EncounterRecord.Practitioner> author) {
        Instant now = time.now();
        FhirDocument doc = new FhirDocument(properties.fhir().identifierSystem(), time.zone(), now,
                UUID.nameUUIDFromBytes((record.encounter().id() + ":" + type.code()).getBytes(StandardCharsets.UTF_8))
                        .toString());
        Reference patient = doc.patient(record.patient());
        Reference organization = doc.organization(properties.hipIdOrPlaceholder(), properties.fhir().hospitalName());
        Reference attending = record.practitioner(record.encounter().attendingDoctorId()).map(doc::practitioner)
                .orElse(null);
        Encounter encounter = doc.encounter(record, patient, organization, attending);
        Reference encounterRef = FhirDocument.reference(encounter);
        Reference authorRef = author.map(doc::practitioner).orElse(organization);

        Composition composition = doc.composition();
        composition.setMeta(new Meta().addProfile(profile));
        composition.setIdentifier(new org.hl7.fhir.r4.model.Identifier()
                .setSystem(properties.fhir().identifierSystem() + "/composition").setValue(composition.getId()));
        composition.setStatus(Composition.CompositionStatus.FINAL);
        composition.setType(compositionType);
        composition.setSubject(patient);
        composition.setEncounter(encounterRef);
        composition.setDateElement(doc.dateTime(documentDate(record)));
        composition.addAuthor(authorRef);
        composition.setCustodian(organization);

        Map<UUID, Reference> diagnoses = new LinkedHashMap<>();
        for (Diagnosis d : record.diagnoses()) {
            Reference ref = doc.add(diagnosis(doc, d, patient, encounterRef));
            diagnoses.put(d.id(), ref);
            Encounter.DiagnosisComponent link = encounter.addDiagnosis().setCondition(ref);
            if (record.isInpatient()) {
                link.setUse("FINAL".equals(d.type())
                        ? concept(Codes.DIAGNOSIS_ROLE, "DD", "Discharge diagnosis")
                        : concept(Codes.DIAGNOSIS_ROLE, "AD", "Admission diagnosis"));
            }
            if (d.primary()) {
                link.setRank(1);
            }
        }
        return new Context(record, doc, patient, encounterRef, encounter, authorRef, organization, diagnoses);
    }

    private static void section(Context c, String title, String code, String display, List<Reference> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Composition.SectionComponent section = c.doc.section(title, code, display);
        entries.forEach(section::addEntry);
    }

    private static Instant documentDate(EncounterRecord record) {
        if (record.admission() != null && record.admission().dischargedAt() != null) {
            return record.admission().dischargedAt();
        }
        return Stream.of(Stream.ofNullable(record.encounter().endedAt()),
                        record.prescriptions().stream().map(Prescription::issuedAt),
                        record.vitals().stream().map(Vitals::recordedAt),
                        record.notes().stream().map(Note::signedAt),
                        Stream.of(record.encounter().startedAt()))
                .flatMap(s -> s).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();
    }

    // ------------------------------------------------------------------ clinical resources

    private static Condition diagnosis(FhirDocument doc, Diagnosis d, Reference patient, Reference encounter) {
        Condition condition = new Condition();
        condition.setId(d.id().toString());
        condition.setMeta(new Meta().addProfile(Codes.NRCES + "Condition"));
        condition.setClinicalStatus(concept(Codes.CONDITION_CLINICAL, "active", "Active"));
        condition.setVerificationStatus(switch (d.type()) {
            case "FINAL" -> concept(Codes.CONDITION_VERIFICATION, "confirmed", "Confirmed");
            case "DIFFERENTIAL" -> concept(Codes.CONDITION_VERIFICATION, "differential", "Differential");
            default -> concept(Codes.CONDITION_VERIFICATION, "provisional", "Provisional");
        });
        condition.addCategory(concept(Codes.CONDITION_CATEGORY, "encounter-diagnosis", "Encounter Diagnosis"));
        CodeableConcept code = new CodeableConcept().setText(d.description());
        if (d.icd10Code() != null) {
            code.addCoding(new Coding(Codes.ICD10, d.icd10Code(), d.description()));
        }
        if (d.snomedCode() != null) {
            code.addCoding(new Coding(Codes.SNOMED, d.snomedCode(), d.description()));
        }
        condition.setCode(code);
        condition.setSubject(patient);
        condition.setEncounter(encounter);
        condition.setRecordedDateElement(doc.dateTime(d.recordedAt()));
        return condition;
    }

    private static List<Reference> complaints(Context c, EncounterRecord record) {
        List<Reference> refs = new ArrayList<>();
        for (Complaint complaint : record.complaints()) {
            Condition condition = new Condition();
            condition.setId(complaint.id().toString());
            condition.setMeta(new Meta().addProfile(Codes.NRCES + "Condition"));
            condition.setClinicalStatus(concept(Codes.CONDITION_CLINICAL, "active", "Active"));
            condition.addCategory(concept(Codes.CONDITION_CATEGORY, "problem-list-item", "Problem List Item"));
            CodeableConcept code = new CodeableConcept().setText(complaint.description());
            if (complaint.snomedCode() != null) {
                code.addCoding(new Coding(Codes.SNOMED, complaint.snomedCode(), complaint.description()));
            }
            condition.setCode(code);
            if (complaint.durationValue() != null) {
                condition.setOnset(new StringType("Since " + complaint.durationValue() + " "
                        + complaint.durationUnit().toLowerCase(Locale.ENGLISH)));
            }
            if (complaint.severity() != null) {
                condition.setSeverity(switch (complaint.severity()) {
                    case "SEVERE" -> concept(Codes.SNOMED, "24484000", "Severe");
                    case "MODERATE" -> concept(Codes.SNOMED, "6736007", "Moderate");
                    default -> concept(Codes.SNOMED, "255604002", "Mild");
                });
            }
            condition.setSubject(c.patient());
            condition.setEncounter(c.encounter());
            condition.setRecordedDateElement(c.doc.dateTime(complaint.recordedAt()));
            refs.add(c.doc.add(condition));
        }
        return refs;
    }

    /** What a measurement is, for the WellnessRecord sections and their NRCES Observation profiles. */
    private enum Measure {
        VITAL_SIGNS("Vital Signs", "ObservationVitalSigns"),
        BODY_MEASUREMENT("Body Measurement", "ObservationBodyMeasurement"),
        GENERAL_ASSESSMENT("General Assessment", "ObservationGeneralAssessment");

        final String title;
        final String profile;

        Measure(String title, String profile) {
            this.title = title;
            this.profile = profile;
        }
    }

    private record Measured(Measure kind, Observation observation) {
    }

    private static List<Reference> vitals(Context c, List<Vitals> rows) {
        return measurements(c, rows).stream().map(m -> c.doc.add(m.observation())).toList();
    }

    /** One Observation per value recorded, reading by reading (not yet added to the document). */
    private static List<Measured> measurements(Context c, List<Vitals> rows) {
        List<Measured> out = new ArrayList<>();
        for (Vitals v : rows) {
            if (v.bpSystolic() != null && v.bpDiastolic() != null) {
                Observation bp = vital(c, v, "bp", "85354-9", "Blood pressure panel with all children optional");
                bp.addComponent().setCode(loinc("8480-6", "Systolic blood pressure"))
                        .setValue(quantity(BigDecimal.valueOf(v.bpSystolic()), "mm[Hg]", "mmHg"));
                bp.addComponent().setCode(loinc("8462-4", "Diastolic blood pressure"))
                        .setValue(quantity(BigDecimal.valueOf(v.bpDiastolic()), "mm[Hg]", "mmHg"));
                out.add(new Measured(Measure.VITAL_SIGNS, bp));
            }
            measure(out, Measure.VITAL_SIGNS, c, v, "pulse", "8867-4", "Heart rate", number(v.pulseBpm()), "/min",
                    "beats/minute");
            measure(out, Measure.VITAL_SIGNS, c, v, "rr", "9279-1", "Respiratory rate", number(v.respiratoryRate()),
                    "/min", "breaths/minute");
            measure(out, Measure.VITAL_SIGNS, c, v, "temp", "8310-5", "Body temperature", v.temperatureC(), "Cel", "°C");
            if (v.spo2Percent() != null) {
                Observation spo2 = vital(c, v, "spo2", "2708-6", "Oxygen saturation in Arterial blood");
                spo2.getCode().addCoding(new Coding(Codes.LOINC, "59408-5",
                        "Oxygen saturation in Arterial blood by Pulse oximetry"));
                spo2.setValue(quantity(BigDecimal.valueOf(v.spo2Percent()), "%", "%"));
                out.add(new Measured(Measure.VITAL_SIGNS, spo2));
            }
            measure(out, Measure.BODY_MEASUREMENT, c, v, "weight", "29463-7", "Body weight", v.weightKg(), "kg", "kg");
            measure(out, Measure.BODY_MEASUREMENT, c, v, "height", "8302-2", "Body height", v.heightCm(), "cm", "cm");
            measure(out, Measure.BODY_MEASUREMENT, c, v, "bmi", "39156-5", "Body mass index (BMI) [Ratio]", v.bmi(),
                    "kg/m2", "kg/m2");
            if (v.bloodGlucoseMgDl() != null) {
                Observation glucose = observation(c, v, "glucose", "2339-0", "Glucose [Mass/volume] in Blood",
                        concept(Codes.OBSERVATION_CATEGORY, "laboratory", "Laboratory"));
                glucose.setValue(quantity(BigDecimal.valueOf(v.bloodGlucoseMgDl()), "mg/dL", "mg/dL"));
                out.add(new Measured(Measure.GENERAL_ASSESSMENT, glucose));
            }
            if (v.painScore() != null) {
                Observation pain = observation(c, v, "pain", "72514-3",
                        "Pain severity - 0-10 verbal numeric rating [Score] - Reported",
                        concept(Codes.OBSERVATION_CATEGORY, "survey", "Survey"));
                pain.setValue(quantity(BigDecimal.valueOf(v.painScore()), "{score}", "score"));
                out.add(new Measured(Measure.GENERAL_ASSESSMENT, pain));
            }
        }
        return out;
    }

    private static void measure(List<Measured> out, Measure kind, Context c, Vitals v, String key, String loinc,
                                String display, BigDecimal value, String ucum, String unit) {
        if (value != null) {
            Observation observation = vital(c, v, key, loinc, display);
            observation.setValue(quantity(value, ucum, unit));
            out.add(new Measured(kind, observation));
        }
    }

    private static Observation vital(Context c, Vitals v, String key, String loinc, String display) {
        return observation(c, v, key, loinc, display, concept(Codes.OBSERVATION_CATEGORY, "vital-signs", "Vital Signs"));
    }

    private static Observation observation(Context c, Vitals v, String key, String loinc, String display,
                                           CodeableConcept category) {
        Observation observation = new Observation();
        observation.setId(c.doc.id(v.id(), key));
        observation.setMeta(new Meta().addProfile(Codes.NRCES + "Observation"));
        observation.setStatus(Observation.ObservationStatus.FINAL);
        observation.addCategory(category);
        observation.setCode(loinc(loinc, display));
        observation.setSubject(c.patient());
        observation.setEncounter(c.encounter());
        observation.setEffective(c.doc.dateTime(v.recordedAt()));
        return observation;
    }

    private static List<Reference> allergies(Context c, EncounterRecord record) {
        List<Reference> refs = new ArrayList<>();
        for (Allergy a : record.allergies()) {
            AllergyIntolerance allergy = new AllergyIntolerance();
            allergy.setId(a.id().toString());
            allergy.setMeta(new Meta().addProfile(Codes.NRCES + "AllergyIntolerance"));
            allergy.setClinicalStatus(concept(Codes.ALLERGY_CLINICAL, "active", "Active"));
            allergy.setVerificationStatus(concept(Codes.ALLERGY_VERIFICATION, "confirmed", "Confirmed"));
            switch (a.allergenType()) {
                case "DRUG", "DRUG_CLASS" -> allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.MEDICATION);
                case "FOOD" -> allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.FOOD);
                case "ENVIRONMENT" -> allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.ENVIRONMENT);
                default -> { }
            }
            if ("LIFE_THREATENING".equals(a.severity())) {
                allergy.setCriticality(AllergyIntolerance.AllergyIntoleranceCriticality.HIGH);
            }
            allergy.setCode(new CodeableConcept().setText(a.allergenText()));
            allergy.setPatient(c.patient());
            allergy.setRecordedDateElement(c.doc.dateTime(a.recordedAt()));
            if (a.reaction() != null) {
                AllergyIntolerance.AllergyIntoleranceReactionComponent reaction = allergy.addReaction()
                        .addManifestation(new CodeableConcept().setText(a.reaction()));
                if (a.severity() != null) {
                    reaction.setSeverity(switch (a.severity()) {
                        case "MILD" -> AllergyIntolerance.AllergyIntoleranceSeverity.MILD;
                        case "MODERATE" -> AllergyIntolerance.AllergyIntoleranceSeverity.MODERATE;
                        default -> AllergyIntolerance.AllergyIntoleranceSeverity.SEVERE;
                    });
                }
            }
            refs.add(c.doc.add(allergy));
        }
        return refs;
    }

    private static List<Reference> serviceRequests(Context c, EncounterRecord record) {
        List<Reference> refs = new ArrayList<>();
        for (Order order : record.orders()) {
            refs.add(c.doc.add(serviceRequest(c, record, order)));
        }
        return refs;
    }

    private static ServiceRequest serviceRequest(Context c, EncounterRecord record, Order order) {
        ServiceRequest request = new ServiceRequest();
        request.setId(order.id().toString());
        request.setMeta(new Meta().addProfile(Codes.NRCES + "ServiceRequest"));
        request.setStatus(switch (order.status()) {
            case "COMPLETED", "REPORTED" -> ServiceRequest.ServiceRequestStatus.COMPLETED;
            default -> ServiceRequest.ServiceRequestStatus.ACTIVE;
        });
        request.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        request.addCategory(switch (order.orderType()) {
            case "RADIOLOGY" -> concept(Codes.SNOMED, "363679005", "Imaging");
            case "PROCEDURE" -> concept(Codes.SNOMED, "387713003", "Surgical procedure");
            default -> concept(Codes.SNOMED, "108252007", "Laboratory procedure");
        });
        request.setCode(new CodeableConcept().setText(order.serviceName()));
        request.setPriority(switch (order.priority()) {
            case "STAT" -> ServiceRequest.ServiceRequestPriority.STAT;
            case "URGENT" -> ServiceRequest.ServiceRequestPriority.URGENT;
            default -> ServiceRequest.ServiceRequestPriority.ROUTINE;
        });
        request.setSubject(c.patient());
        request.setEncounter(c.encounter());
        request.setAuthoredOnElement(c.doc.dateTime(order.orderedAt()));
        record.practitioner(order.orderedBy()).map(c.doc::practitioner).ifPresent(request::setRequester);
        if (order.clinicalIndication() != null) {
            request.addReasonCode(new CodeableConcept().setText(order.clinicalIndication()));
        }
        return request;
    }

    /**
     * One DiagnosticReport per finished investigation, based on its ServiceRequest. Radiology
     * reports also carry the ImagingStudy (DICOM Study Instance UID, modality, series and instance
     * counts), the reporting radiologist and the signed report text; pixel data stays in the PACS.
     */
    private List<Reference> diagnosticReports(Context c, EncounterRecord record, List<EncounterRecord.Report> reports) {
        List<Reference> refs = new ArrayList<>();
        for (EncounterRecord.Report r : reports) {
            Order order = r.order();
            EncounterRecord.Study study = r.study();
            Reference basedOn = c.doc.add(serviceRequest(c, record, order));
            DiagnosticReport report = new DiagnosticReport();
            report.setId(c.doc.id(order.id(), "report"));
            report.setMeta(new Meta().addProfile(Codes.NRCES + (r.isImaging() ? "DiagnosticReportImaging"
                    : "DiagnosticReportLab")));
            report.setStatus(study != null && "ADDENDUM".equals(study.reportStatus())
                    ? DiagnosticReport.DiagnosticReportStatus.AMENDED : DiagnosticReport.DiagnosticReportStatus.FINAL);
            report.addCategory(r.isImaging()
                    ? concept(Codes.V2_DIAGNOSTIC_SERVICE, "RAD", "Radiology")
                    : concept(Codes.V2_DIAGNOSTIC_SERVICE, "LAB", "Laboratory"));
            CodeableConcept code = new CodeableConcept().setText(order.serviceName());
            if (order.loincCode() != null) {
                code.addCoding(new Coding(Codes.LOINC, order.loincCode(), order.loincDisplay()));
            }
            report.setCode(code.addCoding(new Coding(
                    properties.fhir().identifierSystem() + "/service", order.serviceCode(), order.serviceName())));
            report.setSubject(c.patient());
            report.setEncounter(c.encounter());
            // A lab result is effective when the sample was taken; an imaging report when it was issued.
            report.setEffective(c.doc.dateTime(!r.isImaging() && order.sampleCollectedAt() != null
                    ? order.sampleCollectedAt() : r.issuedAt()));
            report.setIssuedElement(c.doc.instant(r.issuedAt()));
            report.addPerformer(c.organization());
            report.addBasedOn(basedOn);
            if (order.resultSummary() != null && !order.resultSummary().isBlank()) {
                report.setConclusion(order.resultSummary());
            }
            if (!r.isImaging()) {
                for (int i = 0; i < order.values().size(); i++) {
                    labValue(c, r, i).map(c.doc::add).ifPresent(report::addResult);
                }
            }
            if (study != null) {
                record.practitioner(study.reportedBy()).map(c.doc::practitioner).ifPresent(report::addResultsInterpreter);
                report.addImagingStudy(c.doc.add(imagingStudy(c, study, basedOn)));
                report.addPresentedForm(new Attachment()
                        .setContentType("text/plain; charset=utf-8")
                        .setLanguage("en-IN")
                        .setTitle(order.serviceName() + " report")
                        .setCreationElement(c.doc.dateTime(study.reportedAt()))
                        .setData(study.reportText().getBytes(StandardCharsets.UTF_8)));
            }
            refs.add(c.doc.add(report));
        }
        return refs;
    }

    private static final Pattern RANGE = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[-–]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern BOUND = Pattern.compile("^\\s*([<>]=?|≤|≥)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    /** Lab units as entered → UCUM codes. Units not listed travel as text only. */
    private static final Map<String, String> UCUM = Map.ofEntries(
            Map.entry("g/dL", "g/dL"), Map.entry("g/L", "g/L"), Map.entry("mg/dL", "mg/dL"), Map.entry("mg/L", "mg/L"),
            Map.entry("ug/dL", "ug/dL"), Map.entry("ng/mL", "ng/mL"), Map.entry("pg/mL", "pg/mL"),
            Map.entry("mmol/L", "mmol/L"), Map.entry("umol/L", "umol/L"), Map.entry("mEq/L", "meq/L"),
            Map.entry("U/L", "U/L"), Map.entry("IU/L", "[IU]/L"), Map.entry("mIU/L", "m[IU]/L"),
            Map.entry("uIU/mL", "u[IU]/mL"), Map.entry("10^3/uL", "10*3/uL"), Map.entry("10^6/uL", "10*6/uL"),
            Map.entry("/uL", "/uL"), Map.entry("%", "%"), Map.entry("fL", "fL"), Map.entry("pg", "pg"),
            Map.entry("mm/h", "mm/h"), Map.entry("s", "s"));

    /** The i-th reported value of a lab test as an Observation, or empty when it has no value. */
    private Optional<Observation> labValue(Context c, EncounterRecord.Report r, int index) {
        Order order = r.order();
        ResultValue v = order.values().get(index);
        if (v.value() == null && (v.text() == null || v.text().isBlank())) {
            return Optional.empty();
        }
        Observation observation = new Observation();
        observation.setId(c.doc.id(order.id(), "value:" + index + ":" + v.code()));
        observation.setMeta(new Meta().addProfile(Codes.NRCES + "Observation"));
        observation.setStatus(Observation.ObservationStatus.FINAL);
        observation.addCategory(concept(Codes.OBSERVATION_CATEGORY, "laboratory", "Laboratory"));
        CodeableConcept code = new CodeableConcept().setText(v.name());
        if (v.loincCode() != null) {
            code.addCoding(new Coding(Codes.LOINC, v.loincCode(), v.loincDisplay()));
        }
        if (v.code() != null) {
            code.addCoding(new Coding(properties.fhir().identifierSystem() + "/lab-parameter",
                    order.serviceCode() + "." + v.code(), v.name()));
        }
        observation.setCode(code);
        observation.setSubject(c.patient());
        observation.setEncounter(c.encounter());
        observation.setEffective(c.doc.dateTime(order.sampleCollectedAt() != null ? order.sampleCollectedAt() : r.issuedAt()));
        observation.setIssuedElement(c.doc.instant(r.issuedAt()));
        observation.addPerformer(c.organization());
        if (v.value() != null) {
            observation.setValue(labQuantity(v.value(), v.unit()));
        } else {
            observation.setValue(new StringType(v.text()));
        }
        Range range = range(v.referenceRange());
        if (range != null || (v.referenceRange() != null && !v.referenceRange().isBlank())) {
            Observation.ObservationReferenceRangeComponent ref = observation.addReferenceRange();
            if (range != null && range.low() != null) {
                ref.setLow(labQuantity(range.low(), v.unit()));
            }
            if (range != null && range.high() != null) {
                ref.setHigh(labQuantity(range.high(), v.unit()));
            }
            if (range == null) {
                ref.setText(v.referenceRange());
            }
        }
        interpretation(v, range).ifPresent(observation::addInterpretation);
        if (v.value() != null && v.text() != null && !v.text().isBlank()) {
            observation.addNote().setText(v.text());
        }
        return Optional.of(observation);
    }

    private record Range(BigDecimal low, BigDecimal high) {
    }

    /** "3.5-5.1" → 3.5 to 5.1; "< 40" → up to 40; "> 60" → from 60; anything else is text. */
    private static Range range(String text) {
        if (text == null) {
            return null;
        }
        Matcher both = RANGE.matcher(text);
        if (both.matches()) {
            return new Range(new BigDecimal(both.group(1)), new BigDecimal(both.group(2)));
        }
        Matcher bound = BOUND.matcher(text);
        if (bound.matches()) {
            BigDecimal limit = new BigDecimal(bound.group(2));
            return bound.group(1).startsWith("<") || bound.group(1).equals("≤") ? new Range(null, limit)
                    : new Range(limit, null);
        }
        return null;
    }

    /** HL7 v3 interpretation: N, L, H, A; a critical value is LL or HH when its side is known, else AA. */
    private static Optional<CodeableConcept> interpretation(ResultValue v, Range range) {
        if (v.flag() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (v.flag()) {
            case "N" -> concept(Codes.OBSERVATION_INTERPRETATION, "N", "Normal");
            case "L" -> concept(Codes.OBSERVATION_INTERPRETATION, "L", "Low");
            case "H" -> concept(Codes.OBSERVATION_INTERPRETATION, "H", "High");
            case "A" -> concept(Codes.OBSERVATION_INTERPRETATION, "A", "Abnormal");
            case "C" -> {
                if (v.value() != null && range != null && range.low() != null && v.value().compareTo(range.low()) < 0) {
                    yield concept(Codes.OBSERVATION_INTERPRETATION, "LL", "Critical low");
                }
                if (v.value() != null && range != null && range.high() != null && v.value().compareTo(range.high()) > 0) {
                    yield concept(Codes.OBSERVATION_INTERPRETATION, "HH", "Critical high");
                }
                yield concept(Codes.OBSERVATION_INTERPRETATION, "AA", "Critical abnormal");
            }
            default -> null;
        });
    }

    private static Quantity labQuantity(BigDecimal value, String unit) {
        Quantity quantity = new Quantity().setValue(value);
        if (unit != null && !unit.isBlank()) {
            quantity.setUnit(unit);
            String ucum = UCUM.get(unit.trim());
            if (ucum != null) {
                quantity.setSystem(Codes.UCUM).setCode(ucum);
            }
        }
        return quantity;
    }

    // ------------------------------------------------------------------ invoices

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * A final bill as an NRCES Invoice. Totals: {@code totalNet} is the taxable value (after
     * discounts, before GST), {@code totalGross} what the patient pays (GST and round-off included).
     * GST is shown as CGST and SGST halves (an intra-state supply).
     */
    private org.hl7.fhir.r4.model.Invoice invoice(Context c, Invoice bill) {
        org.hl7.fhir.r4.model.Invoice invoice = new org.hl7.fhir.r4.model.Invoice();
        invoice.setId(bill.id().toString());
        invoice.setMeta(new Meta().addProfile(Codes.NRCES + "Invoice"));
        invoice.addIdentifier().setSystem(properties.fhir().identifierSystem() + "/bill").setValue(bill.number());
        invoice.setStatus(org.hl7.fhir.r4.model.Invoice.InvoiceStatus.ISSUED);
        invoice.setType(switch (bill.billType()) {
            case "PHARMACY" -> concept(Codes.BILLING, "01", "Pharmacy");
            case "IPD" -> concept(Codes.BILLING, "02", "IPD");
            case "OPD" -> concept(Codes.BILLING, "03", "OPD");
            default -> concept(Codes.BILLING, "99", "Others").setText(billKind(bill.billType()));
        });
        invoice.setSubject(c.patient());
        invoice.setRecipient(c.patient());
        invoice.setDateElement(c.doc.dateTime(bill.issuedAt()));
        invoice.setIssuer(c.organization());
        for (InvoiceLine line : bill.lines()) {
            Reference charge = c.doc.add(chargeItem(c, line));
            org.hl7.fhir.r4.model.Invoice.InvoiceLineItemComponent item = invoice.addLineItem()
                    .setSequence(line.sequence()).setChargeItem(charge);
            price(item, org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType.BASE, "01", "Rate", line.gross(), null);
            if (line.isMedicine()) {
                price(item, org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType.INFORMATIONAL, "00", "MRP",
                        line.mrp().multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP), null);
            }
            if (line.discount().signum() > 0) {
                price(item, org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType.DISCOUNT, "02", "Discount",
                        line.discount(), null);
            }
            if (line.gstRate().signum() > 0) {
                BigDecimal half = line.gstRate().divide(TWO).divide(HUNDRED);
                BigDecimal cgst = line.tax().divide(TWO, 2, RoundingMode.HALF_UP);
                price(item, org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType.TAX, "03", "CGST", cgst, half);
                price(item, org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType.TAX, "04", "SGST",
                        line.tax().subtract(cgst), half);
            }
        }
        invoice.setTotalNet(money(bill.gross().subtract(bill.discount())));
        invoice.setTotalGross(money(bill.payable()));
        if (bill.roundOff().signum() != 0) {
            invoice.addNote().setText("Total ₹" + plain(bill.net()) + " rounded off by ₹" + plain(bill.roundOff())
                    + " to ₹" + plain(bill.payable()));
        }
        return invoice;
    }

    private static void price(org.hl7.fhir.r4.model.Invoice.InvoiceLineItemComponent item,
                              org.hl7.fhir.r4.model.Invoice.InvoicePriceComponentType type, String code, String display,
                              BigDecimal amount, BigDecimal factor) {
        org.hl7.fhir.r4.model.Invoice.InvoiceLineItemPriceComponentComponent component = item.addPriceComponent()
                .setType(type).setCode(concept(Codes.PRICE_COMPONENT, code, display)).setAmount(money(amount));
        if (factor != null) {
            component.setFactor(factor);
        }
    }

    private ChargeItem chargeItem(Context c, InvoiceLine line) {
        ChargeItem item = new ChargeItem();
        item.setId(line.id().toString());
        item.setMeta(new Meta().addProfile(Codes.NRCES + "ChargeItem"));
        item.setStatus(ChargeItem.ChargeItemStatus.BILLED);
        CodeableConcept code = line.isMedicine() ? concept(Codes.BILLING, "05", "Medicines") : switch (line.category()) {
            case "CONSULTATION", "DOCTOR_VISIT" -> concept(Codes.BILLING, "00", "Consultation");
            case "LABORATORY" -> concept(Codes.BILLING, "04", "Pathology");
            case "NURSING" -> concept(Codes.BILLING, "06", "Nursing Charges");
            default -> concept(Codes.BILLING, "99", "Others");
        };
        code.addCoding(new Coding(properties.fhir().identifierSystem() + "/service", line.serviceCode(),
                line.serviceName()));
        item.setCode(code.setText(line.description()));
        item.setSubject(c.patient());
        item.setContext(c.encounter());
        item.setOccurrence(new DateTimeType(line.serviceDate().toString()));
        Quantity quantity = new Quantity().setValue(line.quantity());
        if (line.unit() != null) {
            quantity.setUnit(line.unit());
        }
        item.setQuantity(quantity);
        if (line.isMedicine()) {
            Medication medication = new Medication();
            medication.setId(c.doc.id(line.id(), "medication"));
            medication.setMeta(new Meta().addProfile(Codes.NRCES + "Medication"));
            medication.setCode(new CodeableConcept().setText(line.medicineName()));
            Medication.MedicationBatchComponent batch = medication.getBatch().setLotNumber(line.batchNumber());
            if (line.expiryDate() != null) {
                batch.setExpirationDateElement(new DateTimeType(line.expiryDate().toString()));
            }
            item.setProduct(c.doc.add(medication));
        }
        return item;
    }

    private static Money money(BigDecimal amount) {
        return new Money().setValue(amount).setCurrency("INR");
    }

    private static String billKind(String billType) {
        return switch (billType) {
            case "PHARMACY" -> "Pharmacy";
            case "IPD" -> "IPD";
            case "OPD" -> "OPD";
            case "EMERGENCY" -> "Emergency";
            default -> "Hospital";
        };
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** An inpatient stay's signed case notes that no other record carries (not the discharge summary or radiology). */
    private static List<Note> caseNotes(EncounterRecord record) {
        if (!record.isInpatient()) {
            return List.of();
        }
        return record.notes().stream()
                .filter(n -> !"DISCHARGE_SUMMARY".equals(n.noteType()) && !"RADIOLOGY_REPORT".equals(n.noteType()))
                .toList();
    }

    private static org.hl7.fhir.r4.model.ImagingStudy imagingStudy(Context c, EncounterRecord.Study s, Reference basedOn) {
        org.hl7.fhir.r4.model.ImagingStudy study = new org.hl7.fhir.r4.model.ImagingStudy();
        study.setId(s.id().toString());
        study.setMeta(new Meta().addProfile(Codes.NRCES + "ImagingStudy"));
        study.addIdentifier().setSystem("urn:dicom:uid").setValue("urn:oid:" + s.studyInstanceUid());
        if (s.accessionNumber() != null) {
            study.addIdentifier().setType(concept(Codes.V2_IDENTIFIER_TYPE, "ACSN", "Accession ID"))
                    .setValue(s.accessionNumber());
        }
        study.setStatus(org.hl7.fhir.r4.model.ImagingStudy.ImagingStudyStatus.AVAILABLE);
        if (s.modality() != null) {
            study.addModality(new Coding(Codes.DICOM, s.modality(), Modality.display(s.modality())));
        }
        study.setSubject(c.patient());
        study.setEncounter(c.encounter());
        study.addBasedOn(basedOn);
        if (s.studyDate() != null) {
            study.setStartedElement(new DateTimeType(s.studyDate().toString()));
        }
        if (s.seriesCount() != null) {
            study.setNumberOfSeries(s.seriesCount());
        }
        if (s.instanceCount() != null) {
            study.setNumberOfInstances(s.instanceCount());
        }
        if (s.description() != null) {
            study.setDescription(s.description());
        }
        return study;
    }

    private org.hl7.fhir.r4.model.Immunization immunization(Context c, EncounterRecord record,
                                                            EncounterRecord.Immunization i) {
        org.hl7.fhir.r4.model.Immunization immunization = new org.hl7.fhir.r4.model.Immunization();
        immunization.setId(i.id().toString());
        immunization.setMeta(new Meta().addProfile(Codes.NRCES + "Immunization"));
        if (i.isCompleted()) {
            immunization.setStatus(org.hl7.fhir.r4.model.Immunization.ImmunizationStatus.COMPLETED);
        } else {
            immunization.setStatus(org.hl7.fhir.r4.model.Immunization.ImmunizationStatus.NOTDONE);
            immunization.setStatusReason(new CodeableConcept().setText(i.notDoneReason()));
        }
        immunization.setVaccineCode(vaccineCode(i));
        immunization.setPatient(c.patient());
        immunization.setEncounter(c.encounter());
        immunization.setOccurrence(c.doc.dateTime(i.administeredAt()));
        immunization.setRecordedElement(c.doc.dateTime(i.recordedAt()));
        immunization.setPrimarySource(i.primarySource());
        if (!i.primarySource()) {
            immunization.setReportOrigin(new CodeableConcept().setText(i.reportOrigin()));
        }
        if (i.manufacturer() != null) {
            immunization.setManufacturer(new Reference().setDisplay(i.manufacturer()));
        }
        if (i.lotNumber() != null) {
            immunization.setLotNumber(i.lotNumber());
        }
        if (i.expiryDate() != null) {
            immunization.setExpirationDateElement(new org.hl7.fhir.r4.model.DateType(i.expiryDate().toString()));
        }
        if (i.site() != null) {
            immunization.setSite(new CodeableConcept().setText(sentence(i.site())));
        }
        if (i.route() != null) {
            immunization.setRoute(switch (i.route()) {
                case "IM" -> concept(Codes.SNOMED, "78421000", "Intramuscular route");
                case "SC" -> concept(Codes.SNOMED, "34206005", "Subcutaneous route");
                case "ORAL" -> concept(Codes.SNOMED, "26643006", "Oral route");
                case "ID" -> concept(Codes.SNOMED, "372464004", "Intradermal route");
                default -> concept(Codes.SNOMED, "46713006", "Nasal route");
            });
        }
        if (i.doseMl() != null && i.isCompleted()) {
            immunization.setDoseQuantity(new Quantity().setValue(i.doseMl()).setUnit("mL").setSystem(Codes.UCUM)
                    .setCode("mL"));
        }
        record.practitioner(i.administeredBy()).map(c.doc::practitioner).ifPresent(p -> immunization.addPerformer()
                .setFunction(concept(Codes.V2_PERFORMER_FUNCTION, "AP", "Administering Provider")).setActor(p));
        if (i.notes() != null) {
            immunization.addNote(new Annotation().setText(i.notes()));
        }
        if (i.doseNumber() != null) {
            org.hl7.fhir.r4.model.Immunization.ImmunizationProtocolAppliedComponent protocol = immunization
                    .addProtocolApplied().setDoseNumber(new org.hl7.fhir.r4.model.PositiveIntType(i.doseNumber()));
            if (i.seriesDoses() != null) {
                protocol.setSeriesDoses(new org.hl7.fhir.r4.model.PositiveIntType(i.seriesDoses()));
            }
            protocol.addTargetDisease(new CodeableConcept().setText(i.targetDisease()));
        }
        return immunization;
    }

    /** Next doses due (one recommendation per vaccination that set a due date). */
    private Optional<Reference> recommendation(Context c, List<EncounterRecord.Immunization> given,
                                               Map<UUID, Reference> refs) {
        List<EncounterRecord.Immunization> due = given.stream().filter(i -> i.nextDueDate() != null).toList();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        org.hl7.fhir.r4.model.ImmunizationRecommendation recommendation =
                new org.hl7.fhir.r4.model.ImmunizationRecommendation();
        recommendation.setId(c.doc.id(c.record().encounter().id(), "immunization-recommendation"));
        recommendation.setMeta(new Meta().addProfile(Codes.NRCES + "ImmunizationRecommendation"));
        recommendation.setPatient(c.patient());
        recommendation.setDateElement(c.doc.dateTime(due.get(due.size() - 1).recordedAt()));
        recommendation.setAuthority(c.organization());
        for (EncounterRecord.Immunization i : due) {
            org.hl7.fhir.r4.model.ImmunizationRecommendation.ImmunizationRecommendationRecommendationComponent next =
                    recommendation.addRecommendation()
                            .addVaccineCode(vaccineCode(i))
                            .setTargetDisease(new CodeableConcept().setText(i.targetDisease()))
                            .setForecastStatus(concept(Codes.RECOMMENDATION_STATUS, "due", "Due"));
            next.addDateCriterion().setCode(concept(Codes.LOINC, "30980-7", "Date vaccine due"))
                    .setValueElement(new DateTimeType(i.nextDueDate().toString()));
            if (i.doseNumber() != null) {
                next.setDoseNumber(new org.hl7.fhir.r4.model.PositiveIntType(
                        i.isCompleted() ? i.doseNumber() + 1 : i.doseNumber()));
            }
            if (i.seriesDoses() != null) {
                next.setSeriesDoses(new org.hl7.fhir.r4.model.PositiveIntType(i.seriesDoses()));
            }
            next.addSupportingImmunization(refs.get(i.id()));
        }
        return Optional.of(c.doc.add(recommendation));
    }

    private CodeableConcept vaccineCode(EncounterRecord.Immunization i) {
        return new CodeableConcept().setText(i.vaccineName()).addCoding(new Coding(
                properties.fhir().identifierSystem() + "/vaccine", i.vaccineCode(), i.vaccineName()));
    }

    /** {@code LEFT_THIGH} → "Left thigh". */
    private static String sentence(String constant) {
        String words = constant.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static List<Reference> medications(Context c, EncounterRecord record) {
        List<Reference> reasons = record.diagnoses().stream().filter(Diagnosis::primary)
                .map(d -> c.diagnoses().get(d.id())).toList();
        List<Reference> refs = new ArrayList<>();
        for (Prescription prescription : record.prescriptions()) {
            Reference requester = record.practitioner(prescription.prescribedBy()).map(c.doc::practitioner).orElse(null);
            for (PrescriptionItem item : prescription.items()) {
                MedicationRequest request = new MedicationRequest();
                request.setId(item.id().toString());
                request.setMeta(new Meta().addProfile(Codes.NRCES + "MedicationRequest"));
                request.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
                request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
                request.setMedication(new CodeableConcept().setText(item.drugText()));
                request.setSubject(c.patient());
                request.setEncounter(c.encounter());
                request.setAuthoredOnElement(c.doc.dateTime(prescription.issuedAt()));
                if (requester != null) {
                    request.setRequester(requester);
                }
                reasons.forEach(request::addReasonReference);
                request.addDosageInstruction(dosage(item));
                if (item.quantity() != null) {
                    request.getDispenseRequest().setQuantity(new Quantity().setValue(item.quantity()));
                }
                refs.add(c.doc.add(request));
            }
        }
        return refs;
    }

    static Dosage dosage(PrescriptionItem item) {
        Dosage dosage = new Dosage().setSequence(item.lineNo());
        List<String> text = new ArrayList<>();
        if (item.dose() != null) {
            text.add(item.dose());
        }
        Timing timing = new Timing();
        Timing.TimingRepeatComponent repeat = timing.getRepeat();
        if (item.frequency() != null) {
            text.add(Frequency.label(item.frequency()));
            Frequency.apply(item.frequency(), timing, dosage);
        }
        if (item.timing() != null && !"ANY".equals(item.timing())) {
            text.add(item.timing().replace('_', ' ').toLowerCase(Locale.ENGLISH));
            switch (item.timing()) {
                case "BEFORE_FOOD" -> repeat.addWhen(Timing.EventTiming.AC);
                case "AFTER_FOOD" -> repeat.addWhen(Timing.EventTiming.PC);
                case "WITH_FOOD" -> repeat.addWhen(Timing.EventTiming.C);
                case "BEDTIME" -> repeat.addWhen(Timing.EventTiming.HS);
                default -> dosage.addAdditionalInstruction(new CodeableConcept().setText("on an empty stomach"));
            }
        }
        if (item.durationDays() != null) {
            text.add("for " + item.durationDays() + (item.durationDays() == 1 ? " day" : " days"));
            repeat.setBounds(new org.hl7.fhir.r4.model.Duration().setValue(item.durationDays())
                    .setUnit("days").setSystem(Codes.UCUM).setCode("d"));
        }
        if (item.route() != null) {
            dosage.setRoute(Route.concept(item.route()));
        }
        if (!timing.isEmpty()) {
            dosage.setTiming(timing);
        }
        if (item.instructions() != null) {
            dosage.setPatientInstruction(item.instructions());
            text.add(item.instructions());
        }
        dosage.setText(String.join(", ", text));
        return dosage;
    }

    private static List<Reference> followUp(Context c, EncounterRecord record) {
        List<Reference> refs = new ArrayList<>();
        for (Prescription p : record.prescriptions()) {
            if (p.advice() != null && !p.advice().isBlank()) {
                CarePlan plan = new CarePlan();
                plan.setId(c.doc.id(p.id(), "advice"));
                plan.setMeta(new Meta().addProfile(Codes.NRCES + "CarePlan"));
                plan.setStatus(CarePlan.CarePlanStatus.ACTIVE);
                plan.setIntent(CarePlan.CarePlanIntent.PLAN);
                plan.setTitle("Advice");
                plan.setDescription(p.advice());
                plan.setSubject(c.patient());
                plan.setEncounter(c.encounter());
                plan.setCreatedElement(c.doc.dateTime(p.issuedAt()));
                record.practitioner(p.prescribedBy()).map(c.doc::practitioner).ifPresent(plan::setAuthor);
                refs.add(c.doc.add(plan));
            }
            if (p.followUpDate() != null) {
                Appointment appointment = new Appointment();
                appointment.setId(c.doc.id(p.id(), "follow-up"));
                appointment.setMeta(new Meta().addProfile(Codes.NRCES + "Appointment"));
                appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
                appointment.setDescription("Follow-up visit — " + record.encounter().departmentName());
                appointment.addRequestedPeriod(new Period().setStartElement(new DateTimeType(p.followUpDate().toString())));
                appointment.addParticipant().setActor(c.patient())
                        .setStatus(Appointment.ParticipationStatus.NEEDSACTION);
                appointment.setCreatedElement(c.doc.dateTime(p.issuedAt()));
                refs.add(c.doc.add(appointment));
            }
        }
        return refs;
    }

    private static List<Reference> documents(Context c, List<Note> notes, String typeCode, String typeDisplay) {
        List<Reference> refs = new ArrayList<>();
        for (Note note : notes) {
            DocumentReference document = new DocumentReference();
            document.setId(note.id().toString());
            document.setMeta(new Meta().addProfile(Codes.NRCES + "DocumentReference"));
            document.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
            document.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);
            document.setType(concept(Codes.SNOMED, typeCode, typeDisplay));
            document.setSubject(c.patient());
            document.setDateElement(c.doc.instant(note.signedAt()));
            c.record().practitioner(note.authorId()).map(c.doc::practitioner).ifPresent(document::addAuthor);
            document.setDescription(noteTitle(note.noteType()));
            document.addContent().setAttachment(new Attachment()
                    .setContentType("text/plain; charset=utf-8")
                    .setLanguage("en-IN")
                    .setTitle(noteTitle(note.noteType()))
                    .setData(note.content().getBytes(StandardCharsets.UTF_8)));
            document.getContext().addEncounter(c.encounter());
            refs.add(c.doc.add(document));
        }
        return refs;
    }

    // ------------------------------------------------------------------ small helpers

    private static String noteTitle(String type) {
        return switch (type) {
            case "DISCHARGE_SUMMARY" -> "Discharge summary";
            case "HISTORY" -> "History";
            case "EXAMINATION" -> "Examination";
            case "PROGRESS" -> "Progress note";
            case "PROCEDURE" -> "Procedure note";
            case "NURSING" -> "Nursing note";
            case "ADVICE" -> "Advice";
            case "RADIOLOGY_REPORT" -> "Radiology report";
            default -> "Clinical note";
        };
    }

    private static CodeableConcept loinc(String code, String display) {
        return concept(Codes.LOINC, code, display);
    }

    private static Quantity quantity(BigDecimal value, String ucum, String unit) {
        return new Quantity().setValue(value).setUnit(unit).setSystem(Codes.UCUM).setCode(ucum);
    }

    private static BigDecimal number(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private String displayDate(Instant instant) {
        return DISPLAY_DATE.format(instant.atZone(time.zone()));
    }
}
