package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.tariff.Rate;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Bills and bill lines. Line arithmetic (gross, discount, GST, total) is computed
 * by generated columns and bill totals by trigger; issued bills are immutable and
 * get their GST serial number from the database at finalisation.
 */
@Repository
public class BillRepository {

    /** Charge categories the IPD engine owns: regenerated on every refresh, never added by hand. */
    public static final Set<String> ENGINE_CATEGORIES = Set.of("ADMISSION", "BED", "NURSING");

    public record Header(UUID id, String billNumber, String billType, String status, UUID patientId, UUID encounterId,
                         UUID admissionId, UUID tariffPlanId, BigDecimal grossAmount, BigDecimal discountAmount,
                         BigDecimal taxAmount, BigDecimal netAmount, BigDecimal roundOff, BigDecimal payableAmount,
                         Instant createdAt, String createdBy, Instant finalizedAt, String finalizedBy,
                         Instant cancelledAt, String cancellationReason) {
    }

    public record Line(UUID id, String serviceCode, String serviceName, String category, String sacCode,
                       String description, LocalDate serviceDate, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal gstRate, BigDecimal taxAmount,
                       BigDecimal lineTotal, String discountApprovedBy, String sourceType, UUID sourceId) {
        public boolean engineGenerated() {
            return ENGINE_CATEGORIES.contains(category);
        }
    }

    public record Balance(BigDecimal received, BigDecimal refunded, BigDecimal due) {
    }

    private static final String HEADER = """
            SELECT id, bill_number, bill_type, status, patient_id, encounter_id, admission_id, tariff_plan_id,
                   gross_amount, discount_amount, tax_amount, net_amount, round_off, payable_amount,
                   created_at, created_by, finalized_at, finalized_by, cancelled_at, cancellation_reason
            FROM billing.bill
            """;

    private final JdbcClient jdbc;

    public BillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(String billType, UUID patientId, UUID encounterId, UUID admissionId, UUID planId,
                       String status, String createdBy) {
        return jdbc.sql("""
                INSERT INTO billing.bill (bill_type, patient_id, encounter_id, admission_id, tariff_plan_id, status, created_by)
                VALUES (:type, :patient, :encounter, :admission, :plan, :status, :by)
                RETURNING id
                """)
                .param("type", billType).param("patient", patientId).param("encounter", encounterId)
                .param("admission", admissionId).param("plan", planId).param("status", status).param("by", createdBy)
                .query(UUID.class).single();
    }

    public Optional<Header> find(UUID billId) {
        return jdbc.sql(HEADER + " WHERE id = :id").param("id", billId).query(BillRepository::header).optional();
    }

    /** Locks the bill row for the rest of the transaction (serialises refresh/finalise/payments). */
    public Optional<Header> lock(UUID billId) {
        return jdbc.sql(HEADER + " WHERE id = :id FOR UPDATE").param("id", billId)
                .query(BillRepository::header).optional();
    }

    public Optional<Header> liveIpdBill(UUID admissionId) {
        return jdbc.sql(HEADER + " WHERE admission_id = :id AND bill_type = 'IPD' AND status <> 'CANCELLED'")
                .param("id", admissionId).query(BillRepository::header).optional();
    }

    public Optional<Header> lockLiveIpdBill(UUID admissionId) {
        return jdbc.sql(HEADER + " WHERE admission_id = :id AND bill_type = 'IPD' AND status <> 'CANCELLED' FOR UPDATE")
                .param("id", admissionId).query(BillRepository::header).optional();
    }

    public List<Header> forPatient(UUID patientId) {
        return jdbc.sql(HEADER + " WHERE patient_id = :id ORDER BY created_at DESC LIMIT 50")
                .param("id", patientId).query(BillRepository::header).list();
    }

    /** Number of the live bill already charging this OPD visit, if any (prevents double billing). */
    public Optional<String> billForVisit(UUID visitId) {
        return jdbc.sql("""
                SELECT coalesce(b.bill_number, b.status) FROM billing.bill_item AS i
                JOIN billing.bill AS b ON b.id = i.bill_id
                WHERE i.source_type = 'OPD_VISIT' AND i.source_id = :visit AND b.status <> 'CANCELLED'
                LIMIT 1
                """)
                .param("visit", visitId).query(String.class).optional();
    }

    public UUID addLine(UUID billId, Rate rate, String description, LocalDate serviceDate, BigDecimal quantity,
                        BigDecimal discount, String discountApprovedBy, String sourceType, UUID sourceId,
                        String createdBy) {
        return jdbc.sql("""
                INSERT INTO billing.bill_item
                    (bill_id, service_id, tariff_rate_id, description, service_date, quantity, unit_price,
                     discount_amount, gst_rate, discount_approved_by, source_type, source_id, created_by)
                VALUES (:bill, :service, :rate, :description, :date, :quantity, :price, :discount, :gst, :approver,
                        :sourceType, :sourceId, :by)
                RETURNING id
                """)
                .param("bill", billId).param("service", rate.serviceId()).param("rate", rate.tariffRateId())
                .param("description", description).param("date", serviceDate).param("quantity", quantity)
                .param("price", rate.amount()).param("discount", discount == null ? BigDecimal.ZERO : discount)
                .param("gst", rate.gstRate()).param("approver", discountApprovedBy)
                .param("sourceType", sourceType).param("sourceId", sourceId).param("by", createdBy)
                .query(UUID.class).single();
    }

    public int deleteEngineLines(UUID billId) {
        return jdbc.sql("""
                DELETE FROM billing.bill_item AS i USING billing.service AS s
                WHERE i.bill_id = :bill AND s.id = i.service_id AND s.category IN ('ADMISSION', 'BED', 'NURSING')
                """)
                .param("bill", billId).update();
    }

    public Optional<String> lineCategory(UUID billId, UUID lineId) {
        return jdbc.sql("""
                SELECT s.category FROM billing.bill_item AS i JOIN billing.service AS s ON s.id = i.service_id
                WHERE i.id = :line AND i.bill_id = :bill
                """)
                .param("line", lineId).param("bill", billId).query(String.class).optional();
    }

    public void deleteLine(UUID billId, UUID lineId) {
        jdbc.sql("DELETE FROM billing.bill_item WHERE id = :line AND bill_id = :bill")
                .param("line", lineId).param("bill", billId).update();
    }

    public List<Line> lines(UUID billId) {
        return jdbc.sql("""
                SELECT i.id, s.code, s.name, s.category, s.sac_code, i.description, i.service_date, i.quantity,
                       i.unit_price, i.gross_amount, i.discount_amount, i.gst_rate, i.tax_amount, i.line_total,
                       i.discount_approved_by, i.source_type, i.source_id
                FROM billing.bill_item AS i JOIN billing.service AS s ON s.id = i.service_id
                WHERE i.bill_id = :bill
                ORDER BY CASE s.category WHEN 'REGISTRATION' THEN 0 WHEN 'CONSULTATION' THEN 1 WHEN 'ADMISSION' THEN 2
                         WHEN 'BED' THEN 3 WHEN 'NURSING' THEN 4 WHEN 'DOCTOR_VISIT' THEN 5 ELSE 6 END,
                         i.service_date, i.created_at
                """)
                .param("bill", billId)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getString("sac_code"), rs.getString("description"),
                        rs.getObject("service_date", LocalDate.class), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("discount_amount"), rs.getBigDecimal("gst_rate"),
                        rs.getBigDecimal("tax_amount"), rs.getBigDecimal("line_total"),
                        rs.getString("discount_approved_by"), rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class)))
                .list();
    }

    public Balance balance(UUID billId) {
        return jdbc.sql("SELECT amount_received, amount_refunded, balance_due FROM billing.v_bill_balance WHERE bill_id = :id")
                .param("id", billId)
                .query((rs, n) -> new Balance(rs.getBigDecimal("amount_received"), rs.getBigDecimal("amount_refunded"),
                        rs.getBigDecimal("balance_due")))
                .single();
    }

    /** Issues the bill: the database assigns the serial number (e.g. OPB/26-27/000123). */
    public void finalizeBill(UUID billId, BigDecimal roundOff, String finalizedBy) {
        jdbc.sql("UPDATE billing.bill SET round_off = :roundOff, status = 'FINAL', finalized_by = :by WHERE id = :id")
                .param("roundOff", roundOff).param("by", finalizedBy).param("id", billId).update();
    }

    public void cancel(UUID billId, Instant at, String by, String reason) {
        jdbc.sql("""
                UPDATE billing.bill SET status = 'CANCELLED', cancelled_at = :at, cancelled_by = :by,
                       cancellation_reason = :reason
                WHERE id = :id
                """)
                .param("at", at.atOffset(ZoneOffset.UTC)).param("by", by).param("reason", reason)
                .param("id", billId).update();
    }

    private static Header header(ResultSet rs, int n) throws SQLException {
        return new Header(rs.getObject("id", UUID.class), rs.getString("bill_number"), rs.getString("bill_type"),
                rs.getString("status"), rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("admission_id", UUID.class), rs.getObject("tariff_plan_id", UUID.class),
                rs.getBigDecimal("gross_amount"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("net_amount"), rs.getBigDecimal("round_off"), rs.getBigDecimal("payable_amount"),
                CareContext.instant(rs, "created_at"), rs.getString("created_by"),
                CareContext.instant(rs, "finalized_at"), rs.getString("finalized_by"),
                CareContext.instant(rs, "cancelled_at"), rs.getString("cancellation_reason"));
    }
}
