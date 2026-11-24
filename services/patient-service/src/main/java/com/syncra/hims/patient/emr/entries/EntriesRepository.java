package com.syncra.hims.patient.emr.entries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Diagnoses, allergies and clinical notes ({@code clinical.diagnosis}, {@code clinical.allergy}, {@code clinical.clinical_note}). */
@Repository
public class EntriesRepository {

    public record EncounterFacts(UUID encounterId, UUID patientId, String status, Instant endedAt) {
    }

    public record Diagnosis(UUID id, UUID encounterId, String description, String icd10Code, String snomedCode,
                            String diagnosisType, boolean primary, String source, boolean enteredInError,
                            String diagnosedBy, Instant recordedAt) {
    }

    public record Allergy(UUID id, String allergenType, String allergenText, String drugClass, String reaction,
                          String severity, String status, String recordedBy, Instant recordedAt) {
    }

    public record Note(UUID id, UUID encounterId, String noteType, String content, UUID authorId, String author,
                       Instant signedAt, UUID amendsNoteId, boolean enteredInError, long version, Instant createdAt) {
    }

    private static final String DIAGNOSIS = """
            SELECT id, encounter_id, description, icd10_code, snomed_code, diagnosis_type, is_primary, source,
                   is_entered_in_error, diagnosed_by, created_at FROM clinical.diagnosis
            """;
    private static final String ALLERGY = """
            SELECT id, allergen_type, allergen_text, drug_class, reaction, severity, status, recorded_by, created_at
            FROM clinical.allergy
            """;
    private static final String NOTE = """
            SELECT n.id, n.encounter_id, n.note_type, n.content, n.author_id, s.full_name, n.signed_at, n.amends_note_id,
                   n.is_entered_in_error, n.version, n.created_at
            FROM clinical.clinical_note n JOIN core.staff s ON s.id = n.author_id
            """;

    private final JdbcClient jdbc;

    public EntriesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EncounterFacts> encounter(UUID encounterId) {
        return jdbc.sql("SELECT id, patient_id, status, ended_at FROM clinical.encounter WHERE id = :id")
                .param("id", encounterId)
                .query((rs, n) -> new EncounterFacts(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("status"), instant(rs, "ended_at"))).optional();
    }

    public Optional<UUID> patientByUhid(String uhid) {
        return jdbc.sql("SELECT id FROM patient.patient WHERE uhid = :uhid AND merged_into_id IS NULL")
                .param("uhid", uhid).query(UUID.class).optional();
    }

    // ------------------------------------------------------------------ diagnoses

    public UUID insertDiagnosis(UUID encounterId, String description, String icd10, String snomed, String type,
                                boolean primary, String source, UUID dictationSessionId, String by) {
        return jdbc.sql("""
                INSERT INTO clinical.diagnosis (encounter_id, description, icd10_code, snomed_code, diagnosis_type,
                                                is_primary, source, dictation_session_id, diagnosed_by)
                VALUES (:e, :d, :icd, :snomed, :type, :primary, :source, :session, :by) RETURNING id
                """).param("e", encounterId).param("d", description).param("icd", icd10).param("snomed", snomed)
                .param("type", type).param("primary", primary).param("source", source).param("session", dictationSessionId)
                .param("by", by).query(UUID.class).single();
    }

    public List<Diagnosis> diagnoses(UUID encounterId) {
        return jdbc.sql(DIAGNOSIS + " WHERE encounter_id = :id ORDER BY is_primary DESC, created_at")
                .param("id", encounterId).query(EntriesRepository::diagnosis).list();
    }

    public Optional<Diagnosis> diagnosis(UUID id) {
        return jdbc.sql(DIAGNOSIS + " WHERE id = :id").param("id", id).query(EntriesRepository::diagnosis).optional();
    }

    public int diagnosisInError(UUID id) {
        return jdbc.sql("UPDATE clinical.diagnosis SET is_entered_in_error = true WHERE id = :id AND NOT is_entered_in_error")
                .param("id", id).update();
    }

    // ------------------------------------------------------------------ allergies

    public UUID insertAllergy(UUID patientId, String type, String text, String drugClass, String reaction,
                              String severity, String by) {
        return jdbc.sql("""
                INSERT INTO clinical.allergy (patient_id, allergen_type, allergen_text, drug_class, reaction, severity,
                                              recorded_by)
                VALUES (:p, :type, :text, :class, :reaction, :severity, :by) RETURNING id
                """).param("p", patientId).param("type", type).param("text", text).param("class", drugClass)
                .param("reaction", reaction).param("severity", severity).param("by", by).query(UUID.class).single();
    }

    public List<Allergy> allergies(UUID patientId) {
        return jdbc.sql(ALLERGY + " WHERE patient_id = :id ORDER BY status = 'ACTIVE' DESC, created_at")
                .param("id", patientId).query(EntriesRepository::allergy).list();
    }

    public Optional<Allergy> allergy(UUID patientId, UUID id) {
        return jdbc.sql(ALLERGY + " WHERE patient_id = :p AND id = :id").param("p", patientId).param("id", id)
                .query(EntriesRepository::allergy).optional();
    }

    public boolean activeAllergyExists(UUID patientId, String text) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM clinical.allergy
                               WHERE patient_id = :p AND status = 'ACTIVE' AND lower(allergen_text) = lower(:t))
                """).param("p", patientId).param("t", text).query(Boolean.class).single();
    }

    public void allergyStatus(UUID id, String status) {
        jdbc.sql("UPDATE clinical.allergy SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    // ------------------------------------------------------------------ notes

    public UUID insertNote(UUID encounterId, String type, String content, UUID authorId, boolean sign, UUID amends) {
        return jdbc.sql("""
                INSERT INTO clinical.clinical_note (encounter_id, note_type, content, author_id, signed_at, amends_note_id)
                VALUES (:e, :type, :content, :author, CASE WHEN :sign THEN now() END, :amends) RETURNING id
                """).param("e", encounterId).param("type", type).param("content", content).param("author", authorId)
                .param("sign", sign).param("amends", amends).query(UUID.class).single();
    }

    public List<Note> notes(UUID encounterId) {
        return jdbc.sql(NOTE + " WHERE n.encounter_id = :id ORDER BY n.created_at").param("id", encounterId)
                .query(EntriesRepository::note).list();
    }

    public Optional<Note> lockNote(UUID id) {
        return jdbc.sql(NOTE + " WHERE n.id = :id FOR UPDATE OF n").param("id", id).query(EntriesRepository::note).optional();
    }

    public int updateDraft(UUID id, String content, long version) {
        return jdbc.sql("""
                UPDATE clinical.clinical_note SET content = :c, version = version + 1
                WHERE id = :id AND signed_at IS NULL AND version = :v
                """).param("c", content).param("id", id).param("v", version).update();
    }

    public void sign(UUID id) {
        jdbc.sql("UPDATE clinical.clinical_note SET signed_at = now(), version = version + 1 WHERE id = :id AND signed_at IS NULL")
                .param("id", id).update();
    }

    public int noteInError(UUID id) {
        return jdbc.sql("UPDATE clinical.clinical_note SET is_entered_in_error = true WHERE id = :id AND NOT is_entered_in_error")
                .param("id", id).update();
    }

    // ------------------------------------------------------------------ mapping

    private static Diagnosis diagnosis(ResultSet rs, int row) throws SQLException {
        return new Diagnosis(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getString("description"), rs.getString("icd10_code"), rs.getString("snomed_code"),
                rs.getString("diagnosis_type"), rs.getBoolean("is_primary"), rs.getString("source"),
                rs.getBoolean("is_entered_in_error"), rs.getString("diagnosed_by"), instant(rs, "created_at"));
    }

    private static Allergy allergy(ResultSet rs, int row) throws SQLException {
        return new Allergy(rs.getObject("id", UUID.class), rs.getString("allergen_type"), rs.getString("allergen_text"),
                rs.getString("drug_class"), rs.getString("reaction"), rs.getString("severity"), rs.getString("status"),
                rs.getString("recorded_by"), instant(rs, "created_at"));
    }

    private static Note note(ResultSet rs, int row) throws SQLException {
        return new Note(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class), rs.getString("note_type"),
                rs.getString("content"), rs.getObject("author_id", UUID.class), rs.getString("full_name"),
                instant(rs, "signed_at"), rs.getObject("amends_note_id", UUID.class), rs.getBoolean("is_entered_in_error"),
                rs.getLong("version"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
