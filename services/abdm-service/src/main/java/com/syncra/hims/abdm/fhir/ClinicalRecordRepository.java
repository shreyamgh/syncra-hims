package com.syncra.hims.abdm.fhir;

import com.syncra.hims.abdm.fhir.EncounterRecord.Admission;
import com.syncra.hims.abdm.fhir.EncounterRecord.Allergy;
import com.syncra.hims.abdm.fhir.EncounterRecord.Complaint;
import com.syncra.hims.abdm.fhir.EncounterRecord.Diagnosis;
import com.syncra.hims.abdm.fhir.EncounterRecord.Encounter;
import com.syncra.hims.abdm.fhir.EncounterRecord.Note;
import com.syncra.hims.abdm.fhir.EncounterRecord.Order;
import com.syncra.hims.abdm.fhir.EncounterRecord.Patient;
import com.syncra.hims.abdm.fhir.EncounterRecord.Practitioner;
import com.syncra.hims.abdm.fhir.EncounterRecord.Prescription;
import com.syncra.hims.abdm.fhir.EncounterRecord.PrescriptionItem;
import com.syncra.hims.abdm.fhir.EncounterRecord.Study;
import com.syncra.hims.abdm.fhir.EncounterRecord.Immunization;
import com.syncra.hims.abdm.fhir.EncounterRecord.Invoice;
import com.syncra.hims.abdm.fhir.EncounterRecord.InvoiceLine;
import com.syncra.hims.abdm.fhir.EncounterRecord.ResultValue;
import com.syncra.hims.abdm.fhir.EncounterRecord.Vitals;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads an encounter's clinical record (read-only, one repeatable snapshot). */
@Repository
public class ClinicalRecordRepository {

    private final JdbcClient jdbc;

    public ClinicalRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<EncounterRecord> load(UUID encounterId) {
        Optional<Encounter> found = jdbc.sql("""
                SELECT e.id, e.encounter_type, e.status, e.started_at, e.ended_at, d.code, d.name, e.attending_doctor_id,
                       e.patient_id, v.doctor_id AS visit_doctor_id
                FROM clinical.encounter e
                JOIN core.department d ON d.id = e.department_id
                LEFT JOIN opd.visit v ON v.encounter_id = e.id
                WHERE e.id = :id
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Encounter(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("encounter_type"),
                        rs.getString("status"), instant(rs, "started_at"), instant(rs, "ended_at"),
                        rs.getString("code"), rs.getString("name"),
                        Optional.ofNullable(rs.getObject("attending_doctor_id", UUID.class))
                                .orElse(rs.getObject("visit_doctor_id", UUID.class))))
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Encounter encounter = found.get();
        UUID patientId = encounter.patientId();

        Patient patient = patient(patientId);
        Admission admission = admission(encounterId);
        List<Prescription> prescriptions = prescriptions(encounterId);
        List<Order> orders = orders(encounterId);
        List<Note> notes = notes(encounterId);
        List<Study> studies = studies(encounterId);
        List<Immunization> immunizations = immunizations(encounterId);

        Set<UUID> staff = new HashSet<>();
        if (encounter.attendingDoctorId() != null) {
            staff.add(encounter.attendingDoctorId());
        }
        if (admission != null) {
            staff.add(admission.admittingDoctorId());
        }
        prescriptions.forEach(p -> staff.add(p.prescribedBy()));
        orders.forEach(o -> staff.add(o.orderedBy()));
        notes.forEach(n -> staff.add(n.authorId()));
        studies.stream().map(Study::reportedBy).filter(java.util.Objects::nonNull).forEach(staff::add);
        immunizations.stream().map(Immunization::administeredBy).filter(java.util.Objects::nonNull).forEach(staff::add);

        return Optional.of(new EncounterRecord(encounter, patient, admission, vitals(encounterId),
                complaints(encounterId), diagnoses(encounterId), allergies(patientId), prescriptions, orders, studies,
                immunizations, notes, practitioners(staff), invoices(encounterId)));
    }

    private Patient patient(UUID patientId) {
        return jdbc.sql("""
                SELECT id, uhid, first_name, middle_name, last_name, full_name, gender, date_of_birth, mobile,
                       abha_number, abha_address, abha_verified_at IS NOT NULL AS abha_verified, address_line,
                       city_village, district_name, state_name, pincode
                FROM patient.patient WHERE id = :id
                """)
                .param("id", patientId)
                .query((rs, n) -> new Patient(rs.getObject("id", UUID.class), rs.getString("uhid"),
                        rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name"),
                        rs.getString("full_name"), rs.getString("gender"),
                        rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                        rs.getString("mobile"), rs.getString("abha_number"), rs.getString("abha_address"),
                        rs.getBoolean("abha_verified"), rs.getString("address_line"), rs.getString("city_village"),
                        rs.getString("district_name"), rs.getString("state_name"), rs.getString("pincode")))
                .single();
    }

    private Admission admission(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, admission_number, status, admitted_at, discharged_at, admitting_doctor_id, provisional_diagnosis
                FROM ipd.admission WHERE encounter_id = :id AND status <> 'CANCELLED'
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Admission(rs.getObject("id", UUID.class), rs.getString("admission_number"),
                        rs.getString("status"), instant(rs, "admitted_at"), instant(rs, "discharged_at"),
                        rs.getObject("admitting_doctor_id", UUID.class), rs.getString("provisional_diagnosis")))
                .optional().orElse(null);
    }

    private List<Vitals> vitals(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, recorded_at, temperature_c, pulse_bpm, respiratory_rate, bp_systolic, bp_diastolic,
                       spo2_percent, weight_kg, height_cm, bmi, blood_glucose_mg_dl, pain_score
                FROM clinical.vital_sign
                WHERE encounter_id = :id AND NOT is_entered_in_error
                ORDER BY recorded_at, created_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Vitals(rs.getObject("id", UUID.class), instant(rs, "recorded_at"),
                        rs.getBigDecimal("temperature_c"), integer(rs, "pulse_bpm"), integer(rs, "respiratory_rate"),
                        integer(rs, "bp_systolic"), integer(rs, "bp_diastolic"), integer(rs, "spo2_percent"),
                        rs.getBigDecimal("weight_kg"), rs.getBigDecimal("height_cm"), rs.getBigDecimal("bmi"),
                        integer(rs, "blood_glucose_mg_dl"), integer(rs, "pain_score")))
                .list();
    }

    private List<Complaint> complaints(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, description, duration_value, duration_unit, severity, snomed_code, is_chief, created_at
                FROM clinical.complaint
                WHERE encounter_id = :id AND NOT is_entered_in_error
                ORDER BY is_chief DESC, created_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Complaint(rs.getObject("id", UUID.class), rs.getString("description"),
                        integer(rs, "duration_value"), rs.getString("duration_unit"), rs.getString("severity"),
                        rs.getString("snomed_code"), rs.getBoolean("is_chief"), instant(rs, "created_at")))
                .list();
    }

    private List<Diagnosis> diagnoses(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, icd10_code, snomed_code, description, diagnosis_type, is_primary, created_at
                FROM clinical.diagnosis
                WHERE encounter_id = :id AND NOT is_entered_in_error
                ORDER BY is_primary DESC, created_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Diagnosis(rs.getObject("id", UUID.class), rs.getString("icd10_code"),
                        rs.getString("snomed_code"), rs.getString("description"), rs.getString("diagnosis_type"),
                        rs.getBoolean("is_primary"), instant(rs, "created_at")))
                .list();
    }

    private List<Allergy> allergies(UUID patientId) {
        return jdbc.sql("""
                SELECT id, allergen_type, allergen_text, reaction, severity, created_at
                FROM clinical.allergy
                WHERE patient_id = :id AND status = 'ACTIVE'
                ORDER BY created_at
                """)
                .param("id", patientId)
                .query((rs, n) -> new Allergy(rs.getObject("id", UUID.class), rs.getString("allergen_type"),
                        rs.getString("allergen_text"), rs.getString("reaction"), rs.getString("severity"),
                        instant(rs, "created_at")))
                .list();
    }

    private List<Prescription> prescriptions(UUID encounterId) {
        List<PrescriptionItem> rows = new ArrayList<>();
        Map<UUID, UUID> itemOwner = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT i.prescription_id, i.id, i.line_no, i.drug_text, i.dose, i.route, i.frequency, i.timing,
                       i.duration_days, i.quantity, i.instructions
                FROM clinical.prescription_item i
                JOIN clinical.prescription p ON p.id = i.prescription_id
                WHERE p.encounter_id = :id AND p.status = 'ISSUED'
                ORDER BY i.prescription_id, i.line_no
                """)
                .param("id", encounterId)
                .query((rs, n) -> {
                    PrescriptionItem item = new PrescriptionItem(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                            rs.getString("drug_text"), rs.getString("dose"), rs.getString("route"),
                            rs.getString("frequency"), rs.getString("timing"), integer(rs, "duration_days"),
                            rs.getBigDecimal("quantity"), rs.getString("instructions"));
                    itemOwner.put(item.id(), rs.getObject("prescription_id", UUID.class));
                    return item;
                })
                .list().forEach(rows::add);
        Map<UUID, List<PrescriptionItem>> byPrescription = rows.stream()
                .collect(Collectors.groupingBy(i -> itemOwner.get(i.id()), LinkedHashMap::new, Collectors.toList()));
        return jdbc.sql("""
                SELECT id, prescribed_by, issued_at, advice, follow_up_date
                FROM clinical.prescription
                WHERE encounter_id = :id AND status = 'ISSUED'
                ORDER BY issued_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Prescription(rs.getObject("id", UUID.class), rs.getObject("prescribed_by", UUID.class),
                        instant(rs, "issued_at"), rs.getString("advice"),
                        rs.getDate("follow_up_date") == null ? null : rs.getDate("follow_up_date").toLocalDate(),
                        byPrescription.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    private List<Order> orders(UUID encounterId) {
        Map<UUID, List<ResultValue>> values = resultValues(encounterId);
        return jdbc.sql("""
                SELECT o.id, s.code, s.name, o.order_type, o.status, o.priority, o.clinical_indication, o.ordered_by,
                       o.ordered_at, o.completed_at, o.result_summary, s.loinc_code, s.loinc_display,
                       o.sample_collected_at
                FROM clinical.service_order o
                JOIN billing.service s ON s.id = o.service_id
                WHERE o.encounter_id = :id AND o.status <> 'CANCELLED'
                ORDER BY o.ordered_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Order(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("order_type"), rs.getString("status"), rs.getString("priority"),
                        rs.getString("clinical_indication"), rs.getObject("ordered_by", UUID.class),
                        instant(rs, "ordered_at"), instant(rs, "completed_at"), rs.getString("result_summary"),
                        rs.getString("loinc_code"), rs.getString("loinc_display"), instant(rs, "sample_collected_at"),
                        values.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    /** Reported values of the encounter's orders, in report order, with each parameter's LOINC code. */
    private Map<UUID, List<ResultValue>> resultValues(UUID encounterId) {
        Map<UUID, List<ResultValue>> byOrder = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT o.id AS order_id, v.value ->> 'code' AS code, v.value ->> 'name' AS name,
                       CASE WHEN jsonb_typeof(v.value -> 'value') = 'number' THEN (v.value ->> 'value')::numeric END AS amount,
                       v.value ->> 'text' AS text, v.value ->> 'unit' AS unit, v.value ->> 'referenceRange' AS reference_range,
                       v.value ->> 'flag' AS flag, a.loinc_code, a.loinc_display
                FROM clinical.service_order o
                CROSS JOIN LATERAL jsonb_array_elements(o.result_values) WITH ORDINALITY AS v (value, ord)
                LEFT JOIN clinical.lab_analyte a ON a.service_id = o.service_id AND a.code = v.value ->> 'code'
                WHERE o.encounter_id = :id AND o.status IN ('REPORTED', 'COMPLETED') AND o.result_values IS NOT NULL
                ORDER BY o.id, v.ord
                """)
                .param("id", encounterId)
                .query((rs, n) -> {
                    byOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                            .add(new ResultValue(rs.getString("code"), rs.getString("name"), rs.getBigDecimal("amount"),
                                    rs.getString("text"), rs.getString("unit"), rs.getString("reference_range"),
                                    rs.getString("flag"), rs.getString("loinc_code"), rs.getString("loinc_display")));
                    return null;
                })
                .list();
        return byOrder;
    }

    /**
     * Final bills of the encounter with their lines; medicines carry their batch, expiry and MRP
     * from the pharmacy dispense they were sold from.
     */
    private List<Invoice> invoices(UUID encounterId) {
        Map<UUID, List<InvoiceLine>> lines = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT i.bill_id, i.id, s.code, s.name, s.category, i.description, i.service_date, i.quantity,
                       i.unit_price, i.gross_amount, i.discount_amount, i.gst_rate, i.tax_amount, i.line_total,
                       dl.mrp, b.batch_number, b.expiry_date, d.dispensing_unit,
                       trim(d.brand_name || ' ' || coalesce(d.strength, '')) AS medicine,
                       row_number() OVER (PARTITION BY i.bill_id ORDER BY i.created_at, i.id) AS sequence
                FROM billing.bill_item i
                JOIN billing.bill bl ON bl.id = i.bill_id
                JOIN billing.service s ON s.id = i.service_id
                LEFT JOIN pharmacy.dispense_line dl ON i.source_type = 'DISPENSE_LINE' AND dl.id = i.source_id
                LEFT JOIN pharmacy.batch b ON b.id = dl.batch_id
                LEFT JOIN pharmacy.drug d ON d.id = b.drug_id
                WHERE bl.encounter_id = :id AND bl.status = 'FINAL'
                ORDER BY i.bill_id, sequence
                """)
                .param("id", encounterId)
                .query((rs, n) -> {
                    lines.computeIfAbsent(rs.getObject("bill_id", UUID.class), k -> new ArrayList<>()).add(new InvoiceLine(
                            rs.getObject("id", UUID.class), rs.getInt("sequence"), rs.getString("code"),
                            rs.getString("name"), rs.getString("category"), rs.getString("description"),
                            rs.getDate("service_date").toLocalDate(), rs.getBigDecimal("quantity"),
                            rs.getString("dispensing_unit"), rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("gross_amount"), rs.getBigDecimal("discount_amount"),
                            rs.getBigDecimal("gst_rate"), rs.getBigDecimal("tax_amount"), rs.getBigDecimal("line_total"),
                            rs.getBigDecimal("mrp"), rs.getString("batch_number"),
                            rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                            rs.getString("mrp") == null ? null : rs.getString("medicine")));
                    return null;
                })
                .list();
        return jdbc.sql("""
                SELECT id, bill_number, bill_type, finalized_at, gross_amount, discount_amount, tax_amount, net_amount,
                       round_off, payable_amount
                FROM billing.bill WHERE encounter_id = :id AND status = 'FINAL'
                ORDER BY finalized_at, bill_number
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Invoice(rs.getObject("id", UUID.class), rs.getString("bill_number"),
                        rs.getString("bill_type"), instant(rs, "finalized_at"), rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("discount_amount"), rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("net_amount"), rs.getBigDecimal("round_off"),
                        rs.getBigDecimal("payable_amount"),
                        lines.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    /** Imaging studies of this encounter's orders, with the signed report text when reported. */
    private List<Study> studies(UUID encounterId) {
        return jdbc.sql("""
                SELECT s.id, s.service_order_id, s.study_instance_uid, s.accession_number, s.modality, s.study_date,
                       s.study_description, s.series_count, s.instance_count, s.report_status, n.content AS report_text,
                       s.reported_by, s.reported_at
                FROM imaging.study s
                JOIN clinical.service_order o ON o.id = s.service_order_id
                LEFT JOIN clinical.clinical_note n ON n.id = s.report_note_id
                     AND n.signed_at IS NOT NULL AND NOT n.is_entered_in_error
                WHERE o.encounter_id = :id AND o.status <> 'CANCELLED'
                ORDER BY s.received_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Study(rs.getObject("id", UUID.class), rs.getObject("service_order_id", UUID.class),
                        rs.getString("study_instance_uid"), rs.getString("accession_number"), rs.getString("modality"),
                        rs.getDate("study_date") == null ? null : rs.getDate("study_date").toLocalDate(),
                        rs.getString("study_description"), integer(rs, "series_count"), integer(rs, "instance_count"),
                        rs.getString("report_status"), rs.getString("report_text"),
                        rs.getObject("reported_by", UUID.class), instant(rs, "reported_at")))
                .list();
    }

    private List<Immunization> immunizations(UUID encounterId) {
        return jdbc.sql("""
                SELECT i.id, i.vaccine_code, v.name, v.target_disease, v.series_doses, i.status, i.not_done_reason,
                       i.dose_number, i.administered_at, i.primary_source, i.report_origin, i.lot_number, i.manufacturer,
                       i.expiry_date, i.route, i.site, i.dose_ml, i.administered_by, i.next_due_date, i.notes, i.created_at
                FROM clinical.immunization i JOIN clinical.vaccine v ON v.code = i.vaccine_code
                WHERE i.encounter_id = :id AND NOT i.is_entered_in_error
                ORDER BY i.administered_at, i.created_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Immunization(rs.getObject("id", UUID.class), rs.getString("vaccine_code"),
                        rs.getString("name"), rs.getString("target_disease"), integer(rs, "series_doses"),
                        rs.getString("status"), rs.getString("not_done_reason"), integer(rs, "dose_number"),
                        instant(rs, "administered_at"), rs.getBoolean("primary_source"), rs.getString("report_origin"),
                        rs.getString("lot_number"), rs.getString("manufacturer"),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                        rs.getString("route"), rs.getString("site"), rs.getBigDecimal("dose_ml"),
                        rs.getObject("administered_by", UUID.class),
                        rs.getDate("next_due_date") == null ? null : rs.getDate("next_due_date").toLocalDate(),
                        rs.getString("notes"), instant(rs, "created_at")))
                .list();
    }

    private List<Note> notes(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, note_type, content, author_id, signed_at
                FROM clinical.clinical_note
                WHERE encounter_id = :id AND signed_at IS NOT NULL AND NOT is_entered_in_error
                  AND NOT EXISTS (SELECT 1 FROM clinical.clinical_note a
                                  WHERE a.amends_note_id = clinical_note.id AND a.signed_at IS NOT NULL
                                    AND NOT a.is_entered_in_error)
                ORDER BY signed_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Note(rs.getObject("id", UUID.class), rs.getString("note_type"),
                        rs.getString("content"), rs.getObject("author_id", UUID.class), instant(rs, "signed_at")))
                .list();
    }

    private Map<UUID, Practitioner> practitioners(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT id, full_name, title, qualification, council_registration, hpr_id
                FROM core.staff WHERE id IN (:ids)
                """)
                .param("ids", List.copyOf(ids))
                .query((rs, n) -> new Practitioner(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("title"), rs.getString("qualification"), rs.getString("council_registration"),
                        rs.getString("hpr_id")))
                .list().stream().collect(Collectors.toMap(Practitioner::id, Function.identity()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }
}
