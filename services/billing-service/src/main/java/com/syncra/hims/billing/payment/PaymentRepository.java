package com.syncra.hims.billing.payment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Receipts and refunds ({@code billing.payment}). Receipt numbers (RCT/RFD) are
 * issued by the database; receipts are immutable apart from voiding and the
 * one-time allocation of an IPD advance to the final bill.
 */
@Repository
public class PaymentRepository {

    public record Payment(UUID id, String receiptNumber, UUID patientId, UUID billId, UUID admissionId, String type,
                          String mode, BigDecimal amount, String referenceNumber, String cardLast4,
                          Instant receivedAt, String receivedBy, String status, String voidReason) {
    }

    public record NewPayment(UUID patientId, UUID billId, UUID admissionId, String type, String mode, BigDecimal amount,
                             String referenceNumber, String cardLast4, String receivedBy, String remarks) {
    }

    private static final String SELECT = """
            SELECT id, receipt_number, patient_id, bill_id, admission_id, payment_type, payment_mode, amount,
                   reference_number, card_last4, received_at, received_by, status, void_reason
            FROM billing.payment
            """;

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Payment insert(NewPayment p) {
        UUID id = jdbc.sql("""
                INSERT INTO billing.payment
                    (patient_id, bill_id, admission_id, payment_type, payment_mode, amount, reference_number,
                     card_last4, received_by, remarks)
                VALUES (:patient, :bill, :admission, :type, :mode, :amount, :reference, :last4, :by, :remarks)
                RETURNING id
                """)
                .param("patient", p.patientId()).param("bill", p.billId()).param("admission", p.admissionId())
                .param("type", p.type()).param("mode", p.mode()).param("amount", p.amount())
                .param("reference", p.referenceNumber()).param("last4", p.cardLast4())
                .param("by", p.receivedBy()).param("remarks", p.remarks())
                .query(UUID.class).single();
        return find(id).orElseThrow();
    }

    public Optional<Payment> find(UUID paymentId) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", paymentId).query(PaymentRepository::map).optional();
    }

    public List<Payment> forBill(UUID billId) {
        return jdbc.sql(SELECT + " WHERE bill_id = :id ORDER BY received_at").param("id", billId)
                .query(PaymentRepository::map).list();
    }

    public List<Payment> unallocatedForAdmission(UUID admissionId) {
        return jdbc.sql(SELECT + " WHERE admission_id = :id AND bill_id IS NULL ORDER BY received_at")
                .param("id", admissionId).query(PaymentRepository::map).list();
    }

    /** Deposits (minus refunds) held against an admission and not yet set off against a bill. */
    public BigDecimal unallocatedAdvance(UUID admissionId) {
        return jdbc.sql("SELECT advance_balance FROM billing.v_unallocated_advance WHERE admission_id = :id")
                .param("id", admissionId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    /** Sets off an admission's completed, unallocated advances against its final bill. */
    public int allocateAdvances(UUID admissionId, UUID billId) {
        return jdbc.sql("""
                UPDATE billing.payment SET bill_id = :bill
                WHERE admission_id = :admission AND bill_id IS NULL AND status = 'COMPLETED' AND payment_type = 'ADVANCE'
                """)
                .param("bill", billId).param("admission", admissionId).update();
    }

    public void voidPayment(UUID paymentId, Instant at, String by, String reason) {
        jdbc.sql("""
                UPDATE billing.payment SET status = 'VOIDED', voided_at = :at, voided_by = :by, void_reason = :reason
                WHERE id = :id
                """)
                .param("at", at.atOffset(ZoneOffset.UTC)).param("by", by).param("reason", reason).param("id", paymentId)
                .update();
    }

    private static Payment map(ResultSet rs, int n) throws SQLException {
        OffsetDateTime received = rs.getObject("received_at", OffsetDateTime.class);
        return new Payment(rs.getObject("id", UUID.class), rs.getString("receipt_number"),
                rs.getObject("patient_id", UUID.class), rs.getObject("bill_id", UUID.class),
                rs.getObject("admission_id", UUID.class), rs.getString("payment_type"), rs.getString("payment_mode"),
                rs.getBigDecimal("amount"), rs.getString("reference_number"), rs.getString("card_last4"),
                received.toInstant(), rs.getString("received_by"), rs.getString("status"), rs.getString("void_reason"));
    }
}
