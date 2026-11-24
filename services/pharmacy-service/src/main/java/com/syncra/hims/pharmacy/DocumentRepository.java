package com.syncra.hims.pharmacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Goods receipts, requisitions, dispenses, prescriptions waiting to be dispensed and the H1 register. */
@Repository
public class DocumentRepository {

    public record Created(UUID id, String number) {
    }

    public record Requisition(UUID id, String number, String requestingStore, String issuingStore, String status,
                              String requestedBy, Instant requestedAt, String issuedBy, Instant issuedAt, List<RequisitionLine> lines) {
    }

    public record RequisitionLine(UUID id, UUID drugId, String drugCode, String brandName, BigDecimal requested, BigDecimal issued) {
    }

    public record Prescription(UUID id, UUID encounterId, UUID patientId, String uhid, String patientName, String gender,
                               LocalDate dateOfBirth, String prescriber, String prescriberRegistration, String encounterType,
                               Instant issuedAt) {
    }

    public record PrescriptionItem(UUID id, int lineNo, UUID drugId, String drugText, String dose, String frequency,
                                   Integer durationDays, BigDecimal quantity) {
    }

    public record DispenseLine(UUID id, UUID prescriptionItemId, String drugCode, String brandName, String strength,
                               String schedule, String batchNumber, LocalDate expiryDate, BigDecimal quantity, String unit,
                               BigDecimal mrp, BigDecimal gstRate) {

        @JsonProperty("amount")
        public BigDecimal amount() {
            return mrp.multiply(quantity).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    public record Dispense(UUID id, String number, UUID prescriptionId, String uhid, String patientName, String prescriber,
                           String dispensedBy, Instant dispensedAt, UUID billId, List<DispenseLine> lines) {

        @JsonProperty("total")
        public BigDecimal total() {
            return lines.stream().map(DispenseLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record RegisterEntry(Instant dispensedAt, String dispenseNumber, String uhid, String patientName, String patientAddress,
                                String prescriber, String prescriberRegistration, String brandName, String strength,
                                String batchNumber, BigDecimal quantity, String unit, String dispensedBy) {
    }

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ goods receipts

    public Created receipt(UUID storeId, String supplier, String invoiceNumber, LocalDate invoiceDate, UUID staffId) {
        return jdbc.sql("""
                INSERT INTO pharmacy.goods_receipt (store_id, supplier, invoice_number, invoice_date, received_by)
                VALUES (:store, :supplier, :invoice, :date, :staff) RETURNING id, receipt_number
                """).param("store", storeId).param("supplier", supplier).param("invoice", invoiceNumber)
                .param("date", invoiceDate).param("staff", staffId)
                .query((rs, n) -> new Created(rs.getObject("id", UUID.class), rs.getString("receipt_number"))).single();
    }

    public void receiptLine(UUID receiptId, UUID batchId, BigDecimal quantity, BigDecimal freeQuantity) {
        jdbc.sql("INSERT INTO pharmacy.goods_receipt_line (receipt_id, batch_id, quantity, free_quantity) VALUES (:r, :b, :q, :f)")
                .param("r", receiptId).param("b", batchId).param("q", quantity).param("f", freeQuantity).update();
    }

    // ------------------------------------------------------------------ requisitions

    public Created requisition(UUID requestingStore, UUID issuingStore, UUID staffId) {
        return jdbc.sql("""
                INSERT INTO pharmacy.requisition (from_store_id, to_store_id, requested_by) VALUES (:from, :to, :staff)
                RETURNING id, requisition_number
                """).param("from", requestingStore).param("to", issuingStore).param("staff", staffId)
                .query((rs, n) -> new Created(rs.getObject("id", UUID.class), rs.getString("requisition_number"))).single();
    }

    public void requisitionLine(UUID requisitionId, UUID drugId, BigDecimal quantity) {
        jdbc.sql("INSERT INTO pharmacy.requisition_line (requisition_id, drug_id, quantity_requested) VALUES (:r, :d, :q)")
                .param("r", requisitionId).param("d", drugId).param("q", quantity).update();
    }

    public List<Requisition> requisitions(List<String> statuses) {
        return jdbc.sql("""
                SELECT r.id FROM pharmacy.requisition r WHERE r.status IN (:statuses) ORDER BY r.requested_at DESC LIMIT 100
                """).param("statuses", statuses).query(UUID.class).list().stream().map(id -> requisition(id).orElseThrow()).toList();
    }

    public Optional<Requisition> lockRequisition(UUID id) {
        jdbc.sql("SELECT 1 FROM pharmacy.requisition WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return requisition(id);
    }

    public Optional<Requisition> requisition(UUID id) {
        List<RequisitionLine> lines = jdbc.sql("""
                SELECT l.id, l.drug_id, d.code, d.brand_name, l.quantity_requested, l.quantity_issued
                FROM pharmacy.requisition_line l JOIN pharmacy.drug d ON d.id = l.drug_id
                WHERE l.requisition_id = :id ORDER BY d.brand_name
                """).param("id", id).query((rs, n) -> new RequisitionLine(rs.getObject("id", UUID.class),
                        rs.getObject("drug_id", UUID.class), rs.getString("code"), rs.getString("brand_name"),
                        rs.getBigDecimal("quantity_requested"), rs.getBigDecimal("quantity_issued"))).list();
        return jdbc.sql("""
                SELECT r.id, r.requisition_number, fs.code AS requesting, ts.code AS issuing, r.status,
                       rb.full_name AS requested_by, r.requested_at, ib.full_name AS issued_by, r.issued_at
                FROM pharmacy.requisition r JOIN pharmacy.store fs ON fs.id = r.from_store_id
                JOIN pharmacy.store ts ON ts.id = r.to_store_id JOIN core.staff rb ON rb.id = r.requested_by
                LEFT JOIN core.staff ib ON ib.id = r.issued_by WHERE r.id = :id
                """).param("id", id).query((rs, n) -> new Requisition(rs.getObject("id", UUID.class),
                        rs.getString("requisition_number"), rs.getString("requesting"), rs.getString("issuing"),
                        rs.getString("status"), rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(),
                        rs.getString("issued_by"), rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant(),
                        lines)).optional();
    }

    public void issued(UUID lineId, BigDecimal quantity) {
        jdbc.sql("UPDATE pharmacy.requisition_line SET quantity_issued = :q WHERE id = :id").param("q", quantity).param("id", lineId).update();
    }

    public void requisitionStatus(UUID id, String status, UUID issuedBy, Instant at) {
        jdbc.sql("UPDATE pharmacy.requisition SET status = :s, issued_by = :by, issued_at = :at WHERE id = :id")
                .param("s", status).param("by", issuedBy).param("at", at == null ? null : Timestamp.from(at)).param("id", id).update();
    }

    // ------------------------------------------------------------------ prescriptions and dispenses

    /** Issued prescriptions since {@code since} with nothing dispensed yet, oldest first. */
    public List<Prescription> pending(Instant since) {
        return jdbc.sql(PRESCRIPTION + """
                 WHERE rx.status = 'ISSUED' AND rx.issued_at >= :since
                   AND NOT EXISTS (SELECT 1 FROM pharmacy.dispense d WHERE d.prescription_id = rx.id)
                ORDER BY rx.issued_at
                LIMIT 100
                """).param("since", Timestamp.from(since)).query(DocumentRepository::prescription).list();
    }

    public Optional<Prescription> lockPrescription(UUID id) {
        jdbc.sql("SELECT 1 FROM clinical.prescription WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return jdbc.sql(PRESCRIPTION + " WHERE rx.id = :id").param("id", id).query(DocumentRepository::prescription).optional();
    }

    public String prescriptionStatus(UUID id) {
        return jdbc.sql("SELECT status FROM clinical.prescription WHERE id = :id").param("id", id).query(String.class).single();
    }

    public boolean dispensed(UUID prescriptionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pharmacy.dispense WHERE prescription_id = :id)").param("id", prescriptionId)
                .query(Boolean.class).single();
    }

    public List<PrescriptionItem> items(UUID prescriptionId) {
        return jdbc.sql("""
                SELECT id, line_no, drug_id, drug_text, dose, frequency, duration_days, quantity
                FROM clinical.prescription_item WHERE prescription_id = :id ORDER BY line_no
                """).param("id", prescriptionId).query((rs, n) -> new PrescriptionItem(rs.getObject("id", UUID.class),
                        rs.getInt("line_no"), rs.getObject("drug_id", UUID.class), rs.getString("drug_text"), rs.getString("dose"),
                        rs.getString("frequency"), (Integer) rs.getObject("duration_days"), rs.getBigDecimal("quantity"))).list();
    }

    public Created dispense(UUID prescriptionId, UUID patientId, UUID storeId, UUID staffId) {
        return jdbc.sql("""
                INSERT INTO pharmacy.dispense (prescription_id, patient_id, store_id, dispensed_by)
                VALUES (:rx, :p, :store, :staff) RETURNING id, dispense_number
                """).param("rx", prescriptionId).param("p", patientId).param("store", storeId).param("staff", staffId)
                .query((rs, n) -> new Created(rs.getObject("id", UUID.class), rs.getString("dispense_number"))).single();
    }

    public void dispenseLine(UUID dispenseId, UUID itemId, UUID drugId, UUID batchId, BigDecimal quantity, BigDecimal mrp,
                             BigDecimal gstRate, String schedule) {
        jdbc.sql("""
                INSERT INTO pharmacy.dispense_line (dispense_id, prescription_item_id, drug_id, batch_id, quantity, mrp, gst_rate, schedule)
                VALUES (:d, :item, :drug, :batch, :q, :mrp, :gst, :schedule)
                """).param("d", dispenseId).param("item", itemId).param("drug", drugId).param("batch", batchId).param("q", quantity)
                .param("mrp", mrp).param("gst", gstRate).param("schedule", schedule).update();
    }

    public Optional<Dispense> dispense(UUID id) {
        List<DispenseLine> lines = jdbc.sql("""
                SELECT l.id, l.prescription_item_id, d.code, d.brand_name, d.strength, l.schedule, b.batch_number, b.expiry_date,
                       l.quantity, d.dispensing_unit, l.mrp, l.gst_rate
                FROM pharmacy.dispense_line l JOIN pharmacy.drug d ON d.id = l.drug_id JOIN pharmacy.batch b ON b.id = l.batch_id
                WHERE l.dispense_id = :id ORDER BY d.brand_name, b.expiry_date
                """).param("id", id).query((rs, n) -> new DispenseLine(rs.getObject("id", UUID.class),
                        rs.getObject("prescription_item_id", UUID.class), rs.getString("code"), rs.getString("brand_name"),
                        rs.getString("strength"), rs.getString("schedule"), rs.getString("batch_number"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("quantity"), rs.getString("dispensing_unit"),
                        rs.getBigDecimal("mrp"), rs.getBigDecimal("gst_rate"))).list();
        return jdbc.sql("""
                SELECT d.id, d.dispense_number, d.prescription_id, p.uhid, p.full_name, concat_ws(' ', ps.title, ps.full_name) AS prescriber,
                       concat_ws(' ', ds.title, ds.full_name) AS dispensed_by, d.dispensed_at, d.bill_id
                FROM pharmacy.dispense d JOIN patient.patient p ON p.id = d.patient_id
                JOIN clinical.prescription rx ON rx.id = d.prescription_id JOIN core.staff ps ON ps.id = rx.prescribed_by
                JOIN core.staff ds ON ds.id = d.dispensed_by WHERE d.id = :id
                """).param("id", id).query((rs, n) -> new Dispense(rs.getObject("id", UUID.class), rs.getString("dispense_number"),
                        rs.getObject("prescription_id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                        rs.getString("prescriber"), rs.getString("dispensed_by"), rs.getTimestamp("dispensed_at").toInstant(),
                        rs.getObject("bill_id", UUID.class), lines)).optional();
    }

    public List<RegisterEntry> scheduleH1(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT * FROM pharmacy.v_schedule_h1_register
                WHERE dispensed_at >= :from AND dispensed_at < :to ORDER BY dispensed_at
                """).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new RegisterEntry(rs.getTimestamp("dispensed_at").toInstant(), rs.getString("dispense_number"),
                        rs.getString("uhid"), rs.getString("patient_name"), rs.getString("patient_address"),
                        rs.getString("prescriber"), rs.getString("prescriber_registration"), rs.getString("brand_name"),
                        rs.getString("strength"), rs.getString("batch_number"), rs.getBigDecimal("quantity"),
                        rs.getString("dispensing_unit"), rs.getString("dispensed_by"))).list();
    }

    private static final String PRESCRIPTION = """
            SELECT rx.id, rx.encounter_id, rx.patient_id, p.uhid, p.full_name, p.gender, p.date_of_birth,
                   concat_ws(' ', ps.title, ps.full_name) AS prescriber, ps.council_registration, e.encounter_type, rx.issued_at
            FROM clinical.prescription rx JOIN patient.patient p ON p.id = rx.patient_id
            JOIN core.staff ps ON ps.id = rx.prescribed_by JOIN clinical.encounter e ON e.id = rx.encounter_id
            """;

    private static Prescription prescription(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Prescription(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("uhid"), rs.getString("full_name"), rs.getString("gender"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(), rs.getString("prescriber"),
                rs.getString("council_registration"), rs.getString("encounter_type"),
                rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant());
    }
}
