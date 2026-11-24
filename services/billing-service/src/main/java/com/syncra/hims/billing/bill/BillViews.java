package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.billing.payment.PaymentRepository;
import com.syncra.hims.billing.tariff.TariffService;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds {@link BillView}s from the bill, its lines, receipts and the patient. */
@Component
public class BillViews {

    private final BillRepository bills;
    private final PaymentRepository payments;
    private final CareContext context;
    private final TariffService tariffs;
    private final HospitalTime time;

    public BillViews(BillRepository bills, PaymentRepository payments, CareContext context, TariffService tariffs,
                     HospitalTime time) {
        this.bills = bills;
        this.payments = payments;
        this.context = context;
        this.tariffs = tariffs;
        this.time = time;
    }

    public BillView view(UUID billId) {
        BillRepository.Header h = bills.find(billId).orElseThrow(() -> ApiException.notFound("Bill", billId));
        CareContext.Patient p = context.patient(h.patientId()).orElseThrow();
        BillRepository.Balance balance = bills.balance(billId);
        String admissionNumber = h.admissionId() == null ? null
                : context.admission(h.admissionId()).map(CareContext.Admission::admissionNumber).orElse(null);
        BigDecimal advance = h.admissionId() == null ? null : payments.unallocatedAdvance(h.admissionId());

        return new BillView(
                h.id(), h.billNumber(), title(h), h.billType(), h.status(),
                new BillView.Patient(p.uhid(), p.name(), p.gender(), time.ageInYears(p.dateOfBirth()),
                        p.address() == null || p.address().isBlank() ? null : p.address()),
                tariffs.plan(h.tariffPlanId()).name(), h.encounterId(), h.admissionId(), admissionNumber,
                bills.lines(billId).stream().map(l -> new BillView.Line(l.id(), l.serviceCode(), l.category(),
                        l.sacCode(), l.description(), l.serviceDate(), l.quantity(), l.unitPrice(), l.grossAmount(),
                        l.discountAmount(), l.gstRate(), l.taxAmount(), l.lineTotal(), l.engineGenerated())).toList(),
                new BillView.Totals(h.grossAmount(), h.discountAmount(), h.taxAmount(), h.netAmount(), h.roundOff(),
                        h.payableAmount(), balance.received(), balance.refunded(), balance.due()),
                payments.forBill(billId).stream().map(r -> new BillView.Receipt(r.id(), r.receiptNumber(), r.type(),
                        r.mode(), r.amount(), r.referenceNumber(), r.receivedAt(), r.receivedBy(), r.status())).toList(),
                advance, h.createdAt(), h.finalizedAt(), h.finalizedBy(), h.cancelledAt(), h.cancellationReason());
    }

    /**
     * Document title. Clinical services are GST-exempt, so a bill with no tax is a
     * "Bill of Supply"; one that also carries taxable lines (e.g. room rent above the
     * exemption threshold) is an "Invoice-cum-Bill of Supply" (CGST Rule 46A).
     */
    static String title(BillRepository.Header h) {
        return switch (h.status()) {
            case "FINAL" -> h.taxAmount().signum() > 0 ? "Invoice-cum-Bill of Supply" : "Bill of Supply";
            case "CANCELLED" -> "Cancelled Bill";
            case "INTERIM" -> "Interim Bill (not a tax document)";
            default -> "Draft Bill";
        };
    }
}
