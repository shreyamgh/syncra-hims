package com.syncra.hims.abdm.linking;

import com.syncra.hims.abdm.linking.PatientMatcher.Candidate;
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

/** Discovery requests, link sessions and the patient-index lookups behind them. */
@Repository
public class LinkingRepository {

    public record Discovery(UUID transactionId, String abhaAddress, UUID patientId, List<UUID> careContextIds,
                            List<String> matchedBy, String outcome, Instant expiresAt) {
    }

    public record Session(UUID id, String linkReference, UUID transactionId, UUID gatewayRequestId, String abhaAddress,
                          UUID patientId, List<UUID> careContextIds, String otpSalt, String otpHash, int attempts,
                          String status, Instant expiresAt) {
    }

    private final JdbcClient jdbc;

    public LinkingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active, living patients sharing a verified ABHA, the verified mobile or the typed UHID. */
    public List<Candidate> candidates(PatientMatcher.Query q) {
        return jdbc.sql("""
                SELECT id, uhid, gender, date_of_birth, dob_is_estimated, mobile, abha_number, abha_address,
                       abha_verified_at IS NOT NULL AS abha_verified,
                       similarity(lower(full_name), lower(COALESCE(CAST(:name AS text), ''))) AS name_score
                FROM patient.patient
                WHERE merged_into_id IS NULL AND is_active AND NOT is_deceased
                  AND ((CAST(:mobile AS text) IS NOT NULL AND mobile = :mobile)
                       OR (CAST(:uhid AS text) IS NOT NULL AND uhid = upper(:uhid))
                       OR (abha_verified_at IS NOT NULL
                           AND ((CAST(:number AS text) IS NOT NULL AND abha_number = :number)
                                OR lower(abha_address) = lower(:address))))
                LIMIT 25
                """)
                .param("name", q.name()).param("mobile", q.verifiedMobile()).param("uhid", q.uhid())
                .param("number", q.verifiedAbhaNumber()).param("address", q.abhaAddress())
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("uhid"),
                        rs.getString("gender"), rs.getDate("date_of_birth") == null ? null
                        : rs.getDate("date_of_birth").toLocalDate(), rs.getBoolean("dob_is_estimated"),
                        rs.getString("mobile"), rs.getString("abha_number"), rs.getString("abha_address"),
                        rs.getBoolean("abha_verified"), rs.getDouble("name_score")))
                .list();
    }

    /** The patient's most recent, not cancelled encounters. */
    public List<UUID> recentEncounters(UUID patientId, int limit) {
        return jdbc.sql("""
                SELECT id FROM clinical.encounter
                WHERE patient_id = :p AND status <> 'CANCELLED'
                ORDER BY started_at DESC LIMIT :limit
                """).param("p", patientId).param("limit", limit).query(UUID.class).list();
    }

    // ------------------------------------------------------------------ discovery

    /** Records the discovery (once per transaction); returns false if the transaction was already seen. */
    public boolean recordDiscovery(UUID transactionId, UUID requestId, String abhaAddress, UUID patientId,
                                   List<UUID> careContextIds, List<String> matchedBy, String outcome, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO abdm.discovery_request (transaction_id, gateway_request_id, abha_address, patient_id,
                                                    care_context_ids, matched_by, outcome, expires_at)
                VALUES (:txn, :request, :address, :patient, CAST(:ids AS uuid[]), CAST(:by AS varchar[]), :outcome, :expires)
                ON CONFLICT (transaction_id) DO NOTHING
                """)
                .param("txn", transactionId).param("request", requestId).param("address", abhaAddress)
                .param("patient", patientId).param("ids", array(careContextIds)).param("by", array(matchedBy))
                .param("outcome", outcome).param("expires", Timestamp.from(expiresAt))
                .update() == 1;
    }

    public Optional<Discovery> discovery(UUID transactionId) {
        return jdbc.sql("""
                SELECT transaction_id, abha_address, patient_id, care_context_ids, matched_by, outcome, expires_at
                FROM abdm.discovery_request WHERE transaction_id = :id
                """).param("id", transactionId)
                .query((rs, n) -> new Discovery(rs.getObject("transaction_id", UUID.class), rs.getString("abha_address"),
                        rs.getObject("patient_id", UUID.class), uuids(rs.getArray("care_context_ids")),
                        Arrays.asList((String[]) rs.getArray("matched_by").getArray()),
                        rs.getString("outcome"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    // ------------------------------------------------------------------ link sessions

    public Session createSession(String linkReference, UUID transactionId, UUID requestId, String abhaAddress,
                                 UUID patientId, List<UUID> careContextIds, String salt, String hash, Instant expiresAt) {
        UUID id = jdbc.sql("""
                INSERT INTO abdm.link_session (link_reference, transaction_id, gateway_request_id, abha_address,
                                               patient_id, care_context_ids, otp_salt, otp_hash, expires_at)
                VALUES (:ref, :txn, :request, :address, :patient, CAST(:ids AS uuid[]), :salt, :hash, :expires)
                RETURNING id
                """)
                .param("ref", linkReference).param("txn", transactionId).param("request", requestId)
                .param("address", abhaAddress).param("patient", patientId).param("ids", array(careContextIds))
                .param("salt", salt).param("hash", hash).param("expires", Timestamp.from(expiresAt))
                .query(UUID.class).single();
        return session("id", id).orElseThrow();
    }

    public Optional<Session> sessionByRequest(UUID requestId) {
        return session("gateway_request_id", requestId);
    }

    public Optional<Session> lockSession(String linkReference) {
        return jdbc.sql("SELECT * FROM abdm.link_session WHERE link_reference = :ref FOR UPDATE")
                .param("ref", linkReference).query(LinkingRepository::mapSession).optional();
    }

    public int sessionsSince(UUID patientId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM abdm.link_session WHERE patient_id = :p AND created_at >= :since")
                .param("p", patientId).param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    public void countFailedAttempt(UUID sessionId, boolean exhausted) {
        jdbc.sql("""
                UPDATE abdm.link_session
                SET attempts = attempts + 1,
                    status = CASE WHEN :exhausted THEN 'FAILED' ELSE status END,
                    failure_reason = CASE WHEN :exhausted THEN 'Too many wrong OTPs' ELSE failure_reason END,
                    completed_at = CASE WHEN :exhausted THEN now() ELSE completed_at END
                WHERE id = :id
                """).param("exhausted", exhausted).param("id", sessionId).update();
    }

    public void close(UUID sessionId, String status, String reason) {
        jdbc.sql("""
                UPDATE abdm.link_session SET status = :status, failure_reason = :reason, completed_at = now()
                WHERE id = :id AND status = 'PENDING'
                """).param("status", status).param("reason", reason).param("id", sessionId).update();
    }

    private Optional<Session> session(String column, UUID value) {
        return jdbc.sql("SELECT * FROM abdm.link_session WHERE " + column + " = :v")
                .param("v", value).query(LinkingRepository::mapSession).optional();
    }

    private static Session mapSession(ResultSet rs, int row) throws SQLException {
        return new Session(rs.getObject("id", UUID.class), rs.getString("link_reference"),
                rs.getObject("transaction_id", UUID.class), rs.getObject("gateway_request_id", UUID.class),
                rs.getString("abha_address"), rs.getObject("patient_id", UUID.class),
                uuids(rs.getArray("care_context_ids")), rs.getString("otp_salt"), rs.getString("otp_hash"),
                rs.getInt("attempts"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant());
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
    }

    private static String array(List<?> values) {
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }
}
