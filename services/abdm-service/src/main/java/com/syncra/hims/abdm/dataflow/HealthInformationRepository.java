package com.syncra.hims.abdm.dataflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.health_information_request} and {@code abdm.health_information_transfer}. */
@Repository
public class HealthInformationRepository {

    public record HiRequest(UUID id, UUID transactionId, UUID gatewayRequestId, UUID consentRowId, Instant dateFrom,
                            Instant dateTo, String dataPushUrl, String hiuPublicKey, String hiuNonce, Instant keyExpiry,
                            String status, String rejectionReason, Instant requestedAt, Instant updatedAt) {
    }

    public record NewRequest(UUID transactionId, UUID gatewayRequestId, UUID consentRowId, Instant dateFrom,
                             Instant dateTo, String dataPushUrl, String hiuPublicKey, String hiuNonce, Instant keyExpiry,
                             String status, String rejectionReason) {
    }

    public record Transfer(UUID careContextId, String hiType, String sha256, int sizeBytes, String status,
                           String errorMessage) {
    }

    private static final String COLUMNS = """
            id, transaction_id, gateway_request_id, consent_id, date_range_from, date_range_to, data_push_url,
            hiu_public_key, hiu_nonce, key_expiry, status, rejection_reason, requested_at, updated_at
            """;

    private final JdbcClient jdbc;

    public HealthInformationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the request unless its transaction is already known; returns the row either way. */
    public HiRequest insertIfNew(NewRequest r) {
        jdbc.sql("""
                INSERT INTO abdm.health_information_request
                    (transaction_id, gateway_request_id, consent_id, date_range_from, date_range_to, data_push_url,
                     hiu_public_key, hiu_nonce, key_expiry, status, rejection_reason)
                VALUES (:txn, :request, :consent, :from, :to, :url, :key, :nonce, :expiry, :status, :reason)
                ON CONFLICT (transaction_id) DO NOTHING
                """)
                .param("txn", r.transactionId()).param("request", r.gatewayRequestId()).param("consent", r.consentRowId())
                .param("from", ts(r.dateFrom())).param("to", ts(r.dateTo())).param("url", r.dataPushUrl())
                .param("key", r.hiuPublicKey()).param("nonce", r.hiuNonce()).param("expiry", ts(r.keyExpiry()))
                .param("status", r.status()).param("reason", r.rejectionReason())
                .update();
        return byTransaction(r.transactionId()).orElseThrow();
    }

    public Optional<HiRequest> byTransaction(UUID transactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.health_information_request WHERE transaction_id = :id")
                .param("id", transactionId).query(HealthInformationRepository::map).optional();
    }

    public Optional<HiRequest> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.health_information_request WHERE id = :id")
                .param("id", id).query(HealthInformationRepository::map).optional();
    }

    public List<HiRequest> forConsent(UUID consentRowId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM abdm.health_information_request WHERE consent_id = :id ORDER BY requested_at DESC")
                .param("id", consentRowId).query(HealthInformationRepository::map).list();
    }

    /** Moves the request from one status to another; false when another worker got there first. */
    public boolean transition(UUID id, String from, String to) {
        return jdbc.sql("""
                UPDATE abdm.health_information_request SET status = :to,
                       completed_at = CASE WHEN :to IN ('TRANSFERRED', 'FAILED') THEN now() END
                WHERE id = :id AND status = :from
                """).param("to", to).param("id", id).param("from", from).update() == 1;
    }

    /** Requests a scheduled sweep should (re)process: unacknowledged, or acknowledged but not started. */
    public List<UUID> due(Instant olderThan, int limit) {
        return jdbc.sql("""
                SELECT r.id FROM abdm.health_information_request r
                WHERE r.updated_at < :before
                  AND (r.status = 'ACKNOWLEDGED'
                       OR (r.status IN ('REQUESTED', 'REJECTED') AND NOT EXISTS (
                               SELECT 1 FROM abdm.gateway_message m
                               WHERE m.direction = 'INBOUND' AND m.request_id = r.gateway_request_id
                                 AND m.outcome = 'ACKED')))
                ORDER BY r.requested_at
                LIMIT :limit
                """).param("before", ts(olderThan)).param("limit", limit).query(UUID.class).list();
    }

    /** Transfers interrupted mid-way (a crash) are reported as failed. */
    public List<UUID> stalled(Instant olderThan) {
        return jdbc.sql("""
                SELECT id FROM abdm.health_information_request WHERE status = 'TRANSFERRING' AND updated_at < :before
                """).param("before", ts(olderThan)).query(UUID.class).list();
    }

    public void recordTransfer(UUID requestId, Transfer t, Instant sentAt) {
        jdbc.sql("""
                INSERT INTO abdm.health_information_transfer
                    (hi_request_id, care_context_id, hi_type, bundle_sha256, bundle_size_bytes, status, error_message, sent_at)
                VALUES (:request, :cc, :type, :sha, :size, :status, :error, :sent)
                ON CONFLICT (hi_request_id, care_context_id, hi_type) DO UPDATE
                SET bundle_sha256 = EXCLUDED.bundle_sha256, bundle_size_bytes = EXCLUDED.bundle_size_bytes,
                    status = EXCLUDED.status, error_message = EXCLUDED.error_message, sent_at = EXCLUDED.sent_at
                """)
                .param("request", requestId).param("cc", t.careContextId()).param("type", t.hiType())
                .param("sha", t.sha256()).param("size", t.sizeBytes()).param("status", t.status())
                .param("error", t.errorMessage()).param("sent", ts(sentAt))
                .update();
    }

    public List<Transfer> transfers(UUID requestId) {
        return jdbc.sql("""
                SELECT care_context_id, hi_type, bundle_sha256, bundle_size_bytes, status, error_message
                FROM abdm.health_information_transfer WHERE hi_request_id = :id ORDER BY created_at
                """).param("id", requestId)
                .query((rs, n) -> new Transfer(rs.getObject("care_context_id", UUID.class), rs.getString("hi_type"),
                        rs.getString("bundle_sha256"), rs.getInt("bundle_size_bytes"), rs.getString("status"),
                        rs.getString("error_message")))
                .list();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static HiRequest map(ResultSet rs, int row) throws SQLException {
        Timestamp expiry = rs.getTimestamp("key_expiry");
        return new HiRequest(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getObject("gateway_request_id", UUID.class), rs.getObject("consent_id", UUID.class),
                rs.getTimestamp("date_range_from").toInstant(), rs.getTimestamp("date_range_to").toInstant(),
                rs.getString("data_push_url"), rs.getString("hiu_public_key"), rs.getString("hiu_nonce"),
                expiry == null ? null : expiry.toInstant(), rs.getString("status"), rs.getString("rejection_reason"),
                rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
