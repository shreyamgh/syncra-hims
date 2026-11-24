package com.syncra.hims.abdm.consent;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.consent_artefact} and its care contexts. */
@Repository
public class ConsentRepository {

    public record Consent(UUID id, UUID artefactId, UUID patientId, String patientAbhaAddress, String hiuId,
                          String purposeCode, List<String> hiTypes, String accessMode, Instant dateFrom, Instant dateTo,
                          Instant eraseAt, String status, Instant receivedAt) {

        public boolean isUsable(Instant now) {
            return "GRANTED".equals(status) && eraseAt.isAfter(now);
        }
    }

    public record NewConsent(UUID artefactId, UUID consentRequestId, UUID patientId, String patientAbhaAddress,
                             String hiuId, String hiuName, String requesterName, String purposeCode, List<String> hiTypes,
                             String accessMode, Instant dateFrom, Instant dateTo, Instant eraseAt, String frequencyUnit,
                             Integer frequencyValue, Integer frequencyRepeats, String signature, String artefactJson) {
    }

    private static final String COLUMNS = """
            id, consent_artefact_id, patient_id, patient_abha_address, hiu_id, purpose_code, hi_types, access_mode,
            date_range_from, date_range_to, data_erase_at, status, received_at
            """;

    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a granted artefact (a re-notification refreshes it); returns the row id. */
    public UUID upsertGranted(NewConsent c) {
        return jdbc.sql("""
                INSERT INTO abdm.consent_artefact
                    (consent_artefact_id, consent_request_id, patient_id, patient_abha_address, hiu_id, hiu_name,
                     requester_name, purpose_code, hi_types, access_mode, date_range_from, date_range_to, data_erase_at,
                     frequency_unit, frequency_value, frequency_repeats, status, signature, artefact)
                VALUES (:artefact, :request, :patient, :address, :hiu, :hiuName, :requester, :purpose,
                        CAST(:types AS varchar[]), :mode, :from, :to, :erase, :unit, :value, :repeats, 'GRANTED',
                        :signature, CAST(:json AS jsonb))
                ON CONFLICT (consent_artefact_id) DO UPDATE
                SET patient_id = EXCLUDED.patient_id, hi_types = EXCLUDED.hi_types, access_mode = EXCLUDED.access_mode,
                    date_range_from = EXCLUDED.date_range_from, date_range_to = EXCLUDED.date_range_to,
                    data_erase_at = EXCLUDED.data_erase_at, signature = EXCLUDED.signature, artefact = EXCLUDED.artefact,
                    status = CASE WHEN abdm.consent_artefact.status = 'GRANTED' THEN 'GRANTED'
                                  ELSE abdm.consent_artefact.status END
                RETURNING id
                """)
                .param("artefact", c.artefactId()).param("request", c.consentRequestId()).param("patient", c.patientId())
                .param("address", c.patientAbhaAddress()).param("hiu", c.hiuId()).param("hiuName", c.hiuName())
                .param("requester", c.requesterName()).param("purpose", c.purposeCode())
                .param("types", "{" + String.join(",", c.hiTypes()) + "}").param("mode", c.accessMode())
                .param("from", ts(c.dateFrom())).param("to", ts(c.dateTo())).param("erase", ts(c.eraseAt()))
                .param("unit", c.frequencyUnit()).param("value", c.frequencyValue()).param("repeats", c.frequencyRepeats())
                .param("signature", c.signature()).param("json", c.artefactJson())
                .query(UUID.class).single();
    }

    public void replaceCareContexts(UUID consentRowId, List<UUID> careContextIds) {
        jdbc.sql("DELETE FROM abdm.consent_care_context WHERE consent_id = :id").param("id", consentRowId).update();
        for (UUID careContextId : careContextIds) {
            jdbc.sql("""
                    INSERT INTO abdm.consent_care_context (consent_id, care_context_id) VALUES (:consent, :cc)
                    ON CONFLICT DO NOTHING
                    """).param("consent", consentRowId).param("cc", careContextId).update();
        }
    }

    /** Revocation, expiry or denial; a revoked consent never becomes granted again. */
    public int changeStatus(UUID artefactId, String status) {
        return jdbc.sql("""
                UPDATE abdm.consent_artefact SET status = :status, status_changed_at = now()
                WHERE consent_artefact_id = :id AND status <> :status
                """).param("status", status).param("id", artefactId).update();
    }

    public Optional<Consent> byArtefactId(UUID artefactId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.consent_artefact WHERE consent_artefact_id = :id")
                .param("id", artefactId).query(ConsentRepository::map).optional();
    }

    public Optional<Consent> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.consent_artefact WHERE id = :id")
                .param("id", id).query(ConsentRepository::map).optional();
    }

    public List<Consent> forPatient(UUID patientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.consent_artefact WHERE patient_id = :id ORDER BY received_at DESC")
                .param("id", patientId).query(ConsentRepository::map).list();
    }

    /** Granted consents past their erase-by time become EXPIRED. */
    public int expire(Instant now) {
        return jdbc.sql("""
                UPDATE abdm.consent_artefact SET status = 'EXPIRED', status_changed_at = now()
                WHERE status = 'GRANTED' AND data_erase_at <= :now
                """).param("now", ts(now)).update();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Consent map(ResultSet rs, int row) throws SQLException {
        Array types = rs.getArray("hi_types");
        return new Consent(rs.getObject("id", UUID.class), rs.getObject("consent_artefact_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("patient_abha_address"), rs.getString("hiu_id"),
                rs.getString("purpose_code"), Arrays.asList((String[]) types.getArray()), rs.getString("access_mode"),
                rs.getTimestamp("date_range_from").toInstant(), rs.getTimestamp("date_range_to").toInstant(),
                rs.getTimestamp("data_erase_at").toInstant(), rs.getString("status"),
                rs.getTimestamp("received_at").toInstant());
    }
}
