package com.syncra.hims.abdm.gateway;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code abdm.gateway_message}: one row per call to or from ABDM, without payloads.
 *
 * <p>Rows are written in transactions of their own, so a failed call is still on
 * record when the business transaction around it rolls back. The unique
 * (direction, REQUEST-ID) pair makes ABDM's retried callbacks recognisable.
 */
@Repository
public class GatewayMessageLog {

    public enum Outcome { RECEIVED, SENT, ACKED, ERROR }

    /** A previously logged outbound call, for matching ABDM's asynchronous reply to it. */
    public record OutboundCall(UUID requestId, String apiPath, UUID patientId, UUID transactionId, Instant occurredAt) {
    }

    /** Result of logging an inbound callback. */
    public record Inbound(boolean firstDelivery, Outcome previousOutcome) {

        /** A retry of a callback that was already processed and answered. */
        public boolean alreadyAnswered() {
            return !firstDelivery && previousOutcome == Outcome.ACKED;
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate independent;

    public GatewayMessageLog(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void outbound(UUID requestId, String apiPath, UUID patientId, UUID transactionId, Integer httpStatus,
                         Outcome outcome, String errorCode, String errorMessage) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO abdm.gateway_message (request_id, direction, api_path, patient_id, transaction_id,
                                                  http_status, outcome, error_code, error_message)
                VALUES (:id, 'OUTBOUND', :path, :patient, :txn, :status, :outcome, :code, :message)
                ON CONFLICT (direction, request_id) DO UPDATE
                    SET http_status = EXCLUDED.http_status, outcome = EXCLUDED.outcome,
                        error_code = EXCLUDED.error_code, error_message = EXCLUDED.error_message,
                        occurred_at = now()
                """)
                .param("id", requestId).param("path", apiPath).param("patient", patientId).param("txn", transactionId)
                .param("status", httpStatus).param("outcome", outcome.name())
                .param("code", truncate(errorCode, 40)).param("message", Redact.text(errorMessage))
                .update());
    }

    /** Records a callback from ABDM; tells whether this REQUEST-ID was seen (and answered) before. */
    public Inbound inbound(UUID requestId, String apiPath, UUID transactionId) {
        return independent.execute(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO abdm.gateway_message (request_id, direction, api_path, transaction_id, outcome)
                    VALUES (:id, 'INBOUND', :path, :txn, 'RECEIVED')
                    ON CONFLICT (direction, request_id) DO NOTHING
                    """)
                    .param("id", requestId).param("path", apiPath).param("txn", transactionId).update();
            if (inserted == 1) {
                return new Inbound(true, null);
            }
            Outcome previous = jdbc.sql("""
                    SELECT outcome FROM abdm.gateway_message WHERE direction = 'INBOUND' AND request_id = :id
                    """).param("id", requestId).query(String.class).optional().map(Outcome::valueOf).orElse(null);
            return new Inbound(false, previous);
        });
    }

    /** Marks a callback as answered ({@link Outcome#ACKED}) or as failed to answer. */
    public void inboundOutcome(UUID requestId, Outcome outcome, String errorCode, String errorMessage) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE abdm.gateway_message
                SET outcome = :outcome, error_code = :code, error_message = :message
                WHERE direction = 'INBOUND' AND request_id = :id
                """)
                .param("outcome", outcome.name()).param("code", truncate(errorCode, 40))
                .param("message", Redact.text(errorMessage)).param("id", requestId)
                .update());
    }

    /** True when the callback with this REQUEST-ID has been answered with its on-* reply. */
    public boolean isAnswered(UUID inboundRequestId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM abdm.gateway_message
                               WHERE direction = 'INBOUND' AND request_id = :id AND outcome = 'ACKED')
                """).param("id", inboundRequestId).query(Boolean.class).single();
    }

    public Optional<OutboundCall> outboundCall(UUID requestId) {
        return jdbc.sql("""
                SELECT request_id, api_path, patient_id, transaction_id, occurred_at
                FROM abdm.gateway_message WHERE direction = 'OUTBOUND' AND request_id = :id
                """)
                .param("id", requestId)
                .query((rs, n) -> new OutboundCall(rs.getObject("request_id", UUID.class), rs.getString("api_path"),
                        rs.getObject("patient_id", UUID.class), rs.getObject("transaction_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()))
                .optional();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
