package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.billing.api.Requests.PaymentInput;
import com.syncra.hims.billing.api.Requests.SettlementType;
import com.syncra.hims.billing.payment.PaymentService;
import com.syncra.hims.billing.tariff.Plan;
import com.syncra.hims.billing.tariff.Rate;
import com.syncra.hims.billing.tariff.TariffService;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pharmacy's GST invoice for a dispense. Medicines are taxable: each line's price is the
 * batch MRP (which includes GST) brought back to the taxable value, and the drug's GST rate is
 * added on, so the line total is the MRP to the paisa (and the bill is rounded to the rupee).
 */
@Service
public class PharmacyBillingService {

    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yyyy");

    private record Dispense(UUID id, String number, UUID patientId, UUID encounterId, UUID billId) {
    }

    private record Line(UUID id, String brandName, String strength, String hsn, String batchNumber, LocalDate expiryDate,
                        BigDecimal quantity, BigDecimal mrp, BigDecimal gstRate) {
    }

    private final JdbcClient jdbc;
    private final BillRepository bills;
    private final TariffService tariffs;
    private final PaymentService payments;
    private final BillViews views;
    private final BillingSupport support;
    private final AuditService audit;
    private final HospitalTime time;

    public PharmacyBillingService(JdbcClient jdbc, BillRepository bills, TariffService tariffs, PaymentService payments,
                                  BillViews views, BillingSupport support, AuditService audit, HospitalTime time) {
        this.jdbc = jdbc;
        this.bills = bills;
        this.tariffs = tariffs;
        this.payments = payments;
        this.views = views;
        this.support = support;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public BillView sell(UUID dispenseId, PaymentInput payment) {
        Dispense dispense = jdbc.sql("""
                SELECT d.id, d.dispense_number, d.patient_id, rx.encounter_id, d.bill_id
                FROM pharmacy.dispense d JOIN clinical.prescription rx ON rx.id = d.prescription_id
                WHERE d.id = :id FOR UPDATE OF d
                """).param("id", dispenseId).query((rs, n) -> new Dispense(rs.getObject("id", UUID.class),
                        rs.getString("dispense_number"), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("bill_id", UUID.class))).optional()
                .orElseThrow(() -> ApiException.notFound("Dispense", dispenseId));
        if (dispense.billId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_BILLED", "This dispense is already billed",
                    Map.of("billId", dispense.billId()));
        }
        List<Line> lines = jdbc.sql("""
                SELECT l.id, dr.brand_name, dr.strength, dr.hsn_code, b.batch_number, b.expiry_date, l.quantity, l.mrp, l.gst_rate
                FROM pharmacy.dispense_line l JOIN pharmacy.drug dr ON dr.id = l.drug_id JOIN pharmacy.batch b ON b.id = l.batch_id
                WHERE l.dispense_id = :id ORDER BY dr.brand_name, b.expiry_date
                """).param("id", dispenseId).query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getString("brand_name"),
                        rs.getString("strength"), rs.getString("hsn_code"), rs.getString("batch_number"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("quantity"), rs.getBigDecimal("mrp"),
                        rs.getBigDecimal("gst_rate"))).list();
        UUID serviceId = jdbc.sql("SELECT id FROM billing.service WHERE code = 'PHM-DRUG' AND is_active").query(UUID.class)
                .optional().orElseThrow(() -> ApiException.unprocessable("NO_PHARMACY_SERVICE", "The medicines service PHM-DRUG is missing"));

        Plan plan = tariffs.requirePlan(null);
        String actor = support.actor();
        LocalDate today = time.today();
        UUID billId = bills.create("PHARMACY", dispense.patientId(), dispense.encounterId(), null, plan.id(), "DRAFT", actor);
        for (Line line : lines) {
            BigDecimal taxable = taxableUnitPrice(line.mrp(), line.gstRate());
            Rate rate = new Rate(serviceId, "PHM-DRUG", "Medicines", "PHARMACY", line.hsn(), "PER_UNIT", null, taxable, line.gstRate());
            String description = line.brandName() + (line.strength() == null ? "" : " " + line.strength())
                    + " · batch " + line.batchNumber() + " · exp " + EXPIRY.format(line.expiryDate()) + " · HSN " + line.hsn();
            bills.addLine(billId, rate, description.length() > 200 ? description.substring(0, 200) : description, today,
                    line.quantity(), BigDecimal.ZERO, null, "DISPENSE_LINE", line.id(), actor);
        }
        BillRepository.Header draft = bills.lock(billId).orElseThrow();
        bills.finalizeBill(billId, BillingSupport.roundOff(draft.netAmount()), actor);
        BillRepository.Header issued = bills.find(billId).orElseThrow();
        jdbc.sql("UPDATE pharmacy.dispense SET bill_id = :bill WHERE id = :id").param("bill", billId).param("id", dispenseId).update();
        audit.record(Action.CREATE, "bill", billId, dispense.patientId(), Map.of("number", issued.billNumber(),
                "dispense", dispense.number()));
        if (payment != null) {
            // At the counter the amount is the invoice total (rounded), which only this call knows.
            PaymentInput paid = payment.amount() != null ? payment : new PaymentInput(payment.mode(),
                    views.view(billId).totals().payable(), payment.reference(), payment.cardLast4(), payment.remarks());
            payments.record(issued, SettlementType.SETTLEMENT, paid);
        }
        return views.view(billId);
    }

    /** MRP includes GST: the taxable unit price is MRP / (1 + rate), to the paisa. */
    static BigDecimal taxableUnitPrice(BigDecimal mrp, BigDecimal gstRate) {
        return mrp.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(gstRate), 2, RoundingMode.HALF_UP);
    }
}
