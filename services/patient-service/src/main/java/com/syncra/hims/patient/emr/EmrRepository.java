package com.syncra.hims.patient.emr;

import com.syncra.hims.patient.emr.dto.ConsultationRequest.ComplaintInput;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.DictationSession;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.PrescriptionItemInput;
import com.syncra.hims.patient.emr.dto.EncounterRecord;
import com.syncra.hims.patient.emr.dto.VitalsInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to the append-only EMR tables (vitals, complaints, prescriptions,
 * dictation provenance). Integrity rules — provenance, immutability of issued
 * prescriptions, value ranges — are enforced again by the schema itself.
 */
@Repository
public class EmrRepository {

    /** Encounter, patient and (for OPD) visit details needed to render and validate a record. */
    public record Header(UUID encounterId, String encounterType, String status, UUID patientId, String uhid,
                         String patientName, String gender, LocalDate dateOfBirth, String departmentCode,
                         String departmentName, UUID visitId, Integer tokenNumber, String queueStatus) {
    }

    private final JdbcClient jdbc;

    public EmrRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Header> header(UUID encounterId) {
        return jdbc.sql("""
                SELECT e.id, e.encounter_type, e.status, p.id AS patient_id, p.uhid, p.full_name, p.gender,
                       p.date_of_birth, d.code, d.name, v.id AS visit_id, v.token_number, v.queue_status
                FROM clinical.encounter AS e
                JOIN patient.patient AS p ON p.id = e.patient_id
                JOIN core.department AS d ON d.id = e.department_id
                LEFT JOIN opd.visit AS v ON v.encounter_id = e.id
                WHERE e.id = :id
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Header(rs.getObject("id", UUID.class), rs.getString("encounter_type"),
                        rs.getString("status"), rs.getObject("patient_id", UUID.class), rs.getString("uhid"),
                        rs.getString("full_name"), rs.getString("gender").trim(),
                        rs.getObject("date_of_birth", LocalDate.class), rs.getString("code"), rs.getString("name"),
                        rs.getObject("visit_id", UUID.class), rs.getObject("token_number", Integer.class),
                        rs.getString("queue_status")))
                .optional();
    }

    // ------------------------------------------------------------------ writes

    /** Idempotent on the AI session id, so a retried save does not fail or duplicate provenance. */
    public UUID upsertDictationSession(UUID encounterId, UUID doctorId, DictationSession s) {
        jdbc.sql("""
                INSERT INTO clinical.dictation_session
                    (ai_session_id, encounter_id, doctor_id, language, engine, audio_seconds, outcome,
                     started_at, ended_at)
                VALUES (:ai, :encounter, :doctor, :language, :engine, :seconds, :outcome, :started, :ended)
                ON CONFLICT (ai_session_id) DO NOTHING
                """)
                .param("ai", s.aiSessionId()).param("encounter", encounterId).param("doctor", doctorId)
                .param("language", s.language()).param("engine", s.engine()).param("seconds", s.audioSeconds())
                .param("outcome", s.outcome()).param("started", at(s.startedAt())).param("ended", at(s.endedAt()))
                .update();
        return jdbc.sql("SELECT id FROM clinical.dictation_session WHERE ai_session_id = :ai AND encounter_id = :e")
                .param("ai", s.aiSessionId()).param("e", encounterId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("dictation session belongs to another encounter"));
    }

    public void insertVitals(UUID encounterId, String recordedBy, VitalsInput v, UUID dictationSessionId) {
        jdbc.sql("""
                INSERT INTO clinical.vital_sign
                    (encounter_id, recorded_by, temperature_c, pulse_bpm, respiratory_rate, bp_systolic,
                     bp_diastolic, spo2_percent, weight_kg, height_cm, blood_glucose_mg_dl, pain_score,
                     source, dictation_session_id)
                VALUES (:encounter, :by, :temp, :pulse, :rr, :sys, :dia, :spo2, :weight, :height, :glucose, :pain,
                        :source, :session)
                """)
                .param("encounter", encounterId).param("by", recordedBy)
                .param("temp", v.temperatureC()).param("pulse", v.pulseBpm()).param("rr", v.respiratoryRate())
                .param("sys", v.bpSystolic()).param("dia", v.bpDiastolic()).param("spo2", v.spo2Percent())
                .param("weight", v.weightKg()).param("height", v.heightCm()).param("glucose", v.bloodGlucoseMgDl())
                .param("pain", v.painScore()).param("source", v.sourceOrManual().name()).param("session", dictationSessionId)
                .update();
    }

    public void insertComplaint(UUID encounterId, String recordedBy, ComplaintInput c, String source, UUID sessionId) {
        jdbc.sql("""
                INSERT INTO clinical.complaint
                    (encounter_id, description, duration_value, duration_unit, severity, is_chief, source,
                     dictation_session_id, recorded_by)
                VALUES (:encounter, :description, :value, :unit, :severity, :chief, :source, :session, :by)
                """)
                .param("encounter", encounterId).param("description", c.description().trim())
                .param("value", c.durationValue())
                .param("unit", c.durationUnit() == null ? null : c.durationUnit().name())
                .param("severity", c.severity() == null ? null : c.severity().name())
                .param("chief", c.chief()).param("source", source).param("session", sessionId).param("by", recordedBy)
                .update();
    }

    public UUID insertDraftPrescription(UUID encounterId, UUID patientId, UUID doctorId, String advice,
                                        LocalDate followUp) {
        return jdbc.sql("""
                INSERT INTO clinical.prescription (encounter_id, patient_id, prescribed_by, advice, follow_up_date)
                VALUES (:encounter, :patient, :doctor, :advice, :followUp)
                RETURNING id
                """)
                .param("encounter", encounterId).param("patient", patientId).param("doctor", doctorId)
                .param("advice", advice).param("followUp", followUp)
                .query(UUID.class).single();
    }

    public void insertPrescriptionItem(UUID prescriptionId, int lineNo, PrescriptionItemInput i, String source,
                                       UUID sessionId) {
        jdbc.sql("""
                INSERT INTO clinical.prescription_item
                    (prescription_id, line_no, drug_id, drug_text, dose, route, frequency, timing, duration_days,
                     quantity, instructions, source, dictation_session_id)
                VALUES (:rx, :line, :drug, :text, :dose, :route, :frequency, :timing, :days, :quantity,
                        :instructions, :source, :session)
                """)
                .param("rx", prescriptionId).param("line", lineNo).param("drug", i.drugId())
                .param("text", i.drugText().trim()).param("dose", i.dose())
                .param("route", i.route() == null ? null : i.route().name())
                .param("frequency", i.frequency() == null ? null : i.frequency().name())
                .param("timing", i.timing() == null ? null : i.timing().name())
                .param("days", i.durationDays()).param("quantity", i.quantity())
                .param("instructions", i.instructions()).param("source", source).param("session", sessionId)
                .update();
    }

    /** DRAFT -> ISSUED; from here the prescription is immutable (database trigger). */
    public void issuePrescription(UUID prescriptionId, Instant at) {
        jdbc.sql("UPDATE clinical.prescription SET status = 'ISSUED', issued_at = :at WHERE id = :id")
                .param("at", at(at)).param("id", prescriptionId).update();
    }

    // ------------------------------------------------------------------ reads

    public List<EncounterRecord.Vitals> vitals(UUID encounterId, int limit) {
        return jdbc.sql("""
                SELECT id, recorded_at, recorded_by, temperature_c, pulse_bpm, respiratory_rate, bp_systolic,
                       bp_diastolic, spo2_percent, weight_kg, height_cm, bmi, blood_glucose_mg_dl, pain_score, source
                FROM clinical.vital_sign
                WHERE encounter_id = :id AND NOT is_entered_in_error
                ORDER BY recorded_at DESC, created_at DESC
                LIMIT :limit
                """)
                .param("id", encounterId).param("limit", limit)
                .query((rs, n) -> new EncounterRecord.Vitals(rs.getObject("id", UUID.class),
                        instant(rs, "recorded_at"), rs.getString("recorded_by"), rs.getBigDecimal("temperature_c"),
                        integer(rs, "pulse_bpm"), integer(rs, "respiratory_rate"), integer(rs, "bp_systolic"),
                        integer(rs, "bp_diastolic"), integer(rs, "spo2_percent"), rs.getBigDecimal("weight_kg"),
                        rs.getBigDecimal("height_cm"), rs.getBigDecimal("bmi"), integer(rs, "blood_glucose_mg_dl"),
                        integer(rs, "pain_score"), rs.getString("source")))
                .list();
    }

    public List<EncounterRecord.Complaint> complaints(UUID encounterId) {
        return jdbc.sql("""
                SELECT id, description, duration_value, duration_unit, severity, is_chief, source
                FROM clinical.complaint
                WHERE encounter_id = :id AND NOT is_entered_in_error
                ORDER BY is_chief DESC, created_at, description
                """)
                .param("id", encounterId)
                .query((rs, n) -> new EncounterRecord.Complaint(rs.getObject("id", UUID.class),
                        rs.getString("description"), integer(rs, "duration_value"), rs.getString("duration_unit"),
                        rs.getString("severity"), rs.getBoolean("is_chief"), rs.getString("source")))
                .list();
    }

    public List<EncounterRecord.Prescription> prescriptions(UUID encounterId) {
        record Head(UUID id, String status, Instant issuedAt, String doctor, String registration, String advice,
                    LocalDate followUp) {
        }
        List<Head> heads = jdbc.sql("""
                SELECT p.id, p.status, p.issued_at, s.title, s.full_name, s.council_registration, p.advice,
                       p.follow_up_date
                FROM clinical.prescription AS p
                JOIN core.staff AS s ON s.id = p.prescribed_by
                WHERE p.encounter_id = :id AND p.status <> 'CANCELLED'
                ORDER BY p.created_at
                """)
                .param("id", encounterId)
                .query((rs, n) -> new Head(rs.getObject("id", UUID.class), rs.getString("status"),
                        instant(rs, "issued_at"),
                        (rs.getString("title") == null ? "" : rs.getString("title") + " ") + rs.getString("full_name"),
                        rs.getString("council_registration"), rs.getString("advice"),
                        rs.getObject("follow_up_date", LocalDate.class)))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<EncounterRecord.PrescriptionItem>> items = jdbc.sql("""
                SELECT prescription_id, line_no, drug_text, dose, route, frequency, timing, duration_days, quantity,
                       instructions, source
                FROM clinical.prescription_item
                WHERE prescription_id IN (:ids)
                ORDER BY line_no
                """)
                .param("ids", heads.stream().map(Head::id).toList())
                .query((rs, n) -> Map.entry(rs.getObject("prescription_id", UUID.class),
                        new EncounterRecord.PrescriptionItem(rs.getInt("line_no"), rs.getString("drug_text"),
                                rs.getString("dose"), rs.getString("route"), rs.getString("frequency"),
                                rs.getString("timing"), integer(rs, "duration_days"), rs.getBigDecimal("quantity"),
                                rs.getString("instructions"), rs.getString("source"))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return heads.stream()
                .map(h -> new EncounterRecord.Prescription(h.id(), h.status(), h.issuedAt(), h.doctor(),
                        h.registration(), h.advice(), h.followUp(), items.getOrDefault(h.id(), List.of())))
                .toList();
    }

    public List<EncounterRecord.Allergy> activeAllergies(UUID patientId) {
        return jdbc.sql("""
                SELECT allergen_text, reaction, severity, drug_class FROM clinical.allergy
                WHERE patient_id = :id AND status = 'ACTIVE'
                ORDER BY created_at
                """)
                .param("id", patientId)
                .query((rs, n) -> new EncounterRecord.Allergy(rs.getString("allergen_text"),
                        rs.getString("reaction"), rs.getString("severity"), rs.getString("drug_class")))
                .list();
    }

    public List<EncounterRecord.CdssAlert> cdssAlerts(UUID encounterId) {
        return jdbc.sql("""
                SELECT alert_type, severity, rule_code, message, resolution, override_reason, kb_version, raised_at
                FROM clinical.cdss_alert WHERE encounter_id = :id
                ORDER BY raised_at, CASE severity WHEN 'CONTRAINDICATED' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'MODERATE' THEN 2 ELSE 3 END
                """).param("id", encounterId)
                .query((rs, n) -> new EncounterRecord.CdssAlert(rs.getString("alert_type"), rs.getString("severity"),
                        rs.getString("rule_code"), rs.getString("message"), rs.getString("resolution"),
                        rs.getString("override_reason"), rs.getString("kb_version"),
                        rs.getTimestamp("raised_at").toInstant()))
                .list();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }
}
