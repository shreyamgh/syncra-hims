package com.syncra.hims.patient.emr.immunization;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code clinical.vaccine} and {@code clinical.immunization}. */
@Repository
public class ImmunizationRepository {

    public record Vaccine(String code, String name, String targetDisease, String defaultRoute, BigDecimal defaultDoseMl,
                          Integer seriesDoses, String programme) {
    }

    public record EncounterFacts(UUID encounterId, UUID patientId, String status, Instant startedAt, Instant endedAt,
                                 LocalDate dateOfBirth) {
    }

    public record Immunization(UUID id, UUID encounterId, String vaccineCode, String vaccineName, String targetDisease,
                               String status, String notDoneReason, Integer doseNumber, Integer seriesDoses,
                               Instant administeredAt, boolean primarySource, String reportOrigin, String lotNumber,
                               String manufacturer, LocalDate expiryDate, String route, String site, BigDecimal doseMl,
                               String administeredBy, LocalDate nextDueDate, String notes, boolean enteredInError,
                               String enteredInErrorReason, Instant recordedAt) {
    }

    private static final String SELECT = """
            SELECT i.id, i.encounter_id, i.vaccine_code, v.name, v.target_disease, i.status, i.not_done_reason,
                   i.dose_number, v.series_doses, i.administered_at, i.primary_source, i.report_origin, i.lot_number,
                   i.manufacturer, i.expiry_date, i.route, i.site, i.dose_ml, s.full_name AS administered_by,
                   i.next_due_date, i.notes, i.is_entered_in_error, i.entered_in_error_reason, i.created_at
            FROM clinical.immunization i
            JOIN clinical.vaccine v ON v.code = i.vaccine_code
            LEFT JOIN core.staff s ON s.id = i.administered_by
            """;

    private final JdbcClient jdbc;

    public ImmunizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Vaccine> vaccines() {
        return jdbc.sql("""
                SELECT code, name, target_disease, default_route, default_dose_ml, series_doses, programme
                FROM clinical.vaccine WHERE is_active ORDER BY programme DESC, code
                """).query(ImmunizationRepository::vaccine).list();
    }

    public Optional<Vaccine> vaccine(String code) {
        return jdbc.sql("""
                SELECT code, name, target_disease, default_route, default_dose_ml, series_doses, programme
                FROM clinical.vaccine WHERE code = :code AND is_active
                """).param("code", code).query(ImmunizationRepository::vaccine).optional();
    }

    public Optional<EncounterFacts> encounter(UUID encounterId) {
        return jdbc.sql("""
                SELECT e.id, e.patient_id, e.status, e.started_at, e.ended_at, p.date_of_birth
                FROM clinical.encounter e JOIN patient.patient p ON p.id = e.patient_id WHERE e.id = :id
                """).param("id", encounterId)
                .query((rs, n) -> new EncounterFacts(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("status"), instant(rs, "started_at"), instant(rs, "ended_at"),
                        rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate()))
                .optional();
    }

    public Optional<UUID> patientByUhid(String uhid) {
        return jdbc.sql("SELECT id FROM patient.patient WHERE uhid = :uhid AND merged_into_id IS NULL")
                .param("uhid", uhid).query(UUID.class).optional();
    }

    public UUID insert(EncounterFacts encounter, ImmunizationInput i, Vaccine vaccine, Instant administeredAt,
                       UUID administeredBy, String recordedBy) {
        return jdbc.sql("""
                INSERT INTO clinical.immunization
                    (encounter_id, patient_id, vaccine_code, status, not_done_reason, dose_number, administered_at,
                     primary_source, report_origin, lot_number, manufacturer, expiry_date, route, site, dose_ml,
                     administered_by, next_due_date, notes, recorded_by)
                VALUES (:encounter, :patient, :vaccine, :status, :reason, :dose, :at, :primary, :origin, :lot,
                        :manufacturer, :expiry, :route, :site, :doseMl, :by, :due, :notes, :recordedBy)
                RETURNING id
                """)
                .param("encounter", encounter.encounterId()).param("patient", encounter.patientId())
                .param("vaccine", vaccine.code()).param("status", i.statusOrDefault().name())
                .param("reason", blankToNull(i.notDoneReason())).param("dose", i.doseNumber())
                .param("at", Timestamp.from(administeredAt)).param("primary", i.isPrimarySource())
                .param("origin", blankToNull(i.reportOrigin())).param("lot", blankToNull(i.lotNumber()))
                .param("manufacturer", blankToNull(i.manufacturer())).param("expiry", i.expiryDate())
                .param("route", i.route() != null ? i.route().name() : vaccine.defaultRoute())
                .param("site", i.site() == null ? null : i.site().name())
                .param("doseMl", i.doseMl() != null ? i.doseMl() : vaccine.defaultDoseMl())
                .param("by", administeredBy).param("due", i.nextDueDate()).param("notes", blankToNull(i.notes()))
                .param("recordedBy", recordedBy)
                .query(UUID.class).single();
    }

    public Optional<Immunization> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id).query(ImmunizationRepository::map).optional();
    }

    public List<Immunization> forEncounter(UUID encounterId) {
        return jdbc.sql(SELECT + " WHERE i.encounter_id = :id ORDER BY i.administered_at, i.created_at")
                .param("id", encounterId).query(ImmunizationRepository::map).list();
    }

    public List<Immunization> forPatient(UUID patientId) {
        return jdbc.sql(SELECT + " WHERE i.patient_id = :id ORDER BY i.administered_at DESC, i.created_at DESC")
                .param("id", patientId).query(ImmunizationRepository::map).list();
    }

    public int markEnteredInError(UUID id, String reason) {
        return jdbc.sql("""
                UPDATE clinical.immunization SET is_entered_in_error = true, entered_in_error_reason = :reason
                WHERE id = :id AND NOT is_entered_in_error
                """).param("reason", reason).param("id", id).update();
    }

    private static Vaccine vaccine(ResultSet rs, int row) throws SQLException {
        return new Vaccine(rs.getString("code"), rs.getString("name"), rs.getString("target_disease"),
                rs.getString("default_route"), rs.getBigDecimal("default_dose_ml"),
                (Integer) rs.getObject("series_doses"), rs.getString("programme"));
    }

    private static Immunization map(ResultSet rs, int row) throws SQLException {
        return new Immunization(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getString("vaccine_code"), rs.getString("name"), rs.getString("target_disease"),
                rs.getString("status"), rs.getString("not_done_reason"), (Integer) rs.getObject("dose_number"),
                (Integer) rs.getObject("series_doses"), instant(rs, "administered_at"), rs.getBoolean("primary_source"),
                rs.getString("report_origin"), rs.getString("lot_number"), rs.getString("manufacturer"),
                rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(), rs.getString("route"),
                rs.getString("site"), rs.getBigDecimal("dose_ml"), rs.getString("administered_by"),
                rs.getDate("next_due_date") == null ? null : rs.getDate("next_due_date").toLocalDate(),
                rs.getString("notes"), rs.getBoolean("is_entered_in_error"), rs.getString("entered_in_error_reason"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
