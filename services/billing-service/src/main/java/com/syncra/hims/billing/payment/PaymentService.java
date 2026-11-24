package com.syncra.hims.billing.payment;

import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.billing.api.Requests.PaymentInput;
import com.syncra.hims.billing.api.Requests.PaymentMode;
import com.syncra.hims.billing.api.Requests.SettlementType;
import com.syncra.hims.billing.bill.BillRepository;
import com.syncra.hims.billing.bill.BillViews;
import com.syncra.hims.billing.bill.CareContext;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money in and out: settlements and refunds against final bills, IPD advances,
 * and voiding of receipts by an administrator. Never accepts more than is due or
 * refunds more than the credit on a bill.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final BillRepository bills;
    private final CareContext context;
    private final BillViews views;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    public PaymentService(PaymentRepository payments, BillRepository bills, CareContext context, BillViews views,
                          CurrentUser currentUser, AuditService audit, HospitalTime time) {
        this.payments = payments;
        this.bills = bills;
        this.context = context;
        this.views = views;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public BillView settle(UUID billId, SettlementType type, PaymentInput input) {
        BillRepository.Header bill = bills.lock(billId).orElseThrow(() -> ApiException.notFound("Bill", billId));
        if (!"FINAL".equals(bill.status())) {
            throw ApiException.conflict("BILL_NOT_FINAL", type == SettlementType.REFUND
                    ? "Refunds are made against a final bill"
                    : "Only a final bill can be settled; take an advance on an open IPD bill instead");
        }
        record(bill, type, input);
        return views.view(billId);
    }

    /** Records a settlement or refund on a locked final bill (also used when issuing OPD slips). */
    public PaymentRepository.Payment record(BillRepository.Header bill, SettlementType type, PaymentInput input) {
        requireReference(input);
        BigDecimal due = bills.balance(bill.id()).due();
        BigDecimal amount;
        if (type == SettlementType.SETTLEMENT) {
            if (due.signum() <= 0) {
                throw ApiException.unprocessable("NOTHING_DUE", "Nothing is due on bill " + bill.billNumber());
            }
            amount = input.amount() == null ? due : input.amount();
            if (amount.compareTo(due) > 0) {
                throw ApiException.unprocessable("OVERPAYMENT", "Only Rs " + due.toPlainString() + " is due");
            }
        } else {
            BigDecimal credit = due.negate();
            if (credit.signum() <= 0) {
                throw ApiException.unprocessable("NO_CREDIT", "Bill " + bill.billNumber() + " has no credit to refund");
            }
            amount = input.amount() == null ? credit : input.amount();
            if (amount.compareTo(credit) > 0) {
                throw ApiException.unprocessable("OVER_REFUND", "Only Rs " + credit.toPlainString() + " can be refunded");
            }
        }
        PaymentRepository.Payment payment = payments.insert(new PaymentRepository.NewPayment(
                bill.patientId(), bill.id(), bill.admissionId(), type.name(), input.mode().name(), amount,
                blankToNull(input.reference()), input.cardLast4(), currentUser.subject(), blankToNull(input.remarks())));
        audit.record(Action.CREATE, "payment", payment.receiptNumber(), bill.patientId(),
                Map.of("type", type.name(), "mode", input.mode().name(), "bill", String.valueOf(bill.billNumber())));
        return payment;
    }

    /** Deposit taken while the patient is admitted, set off against the final bill later. */
    @Transactional
    public PaymentRepository.Payment advance(UUID admissionId, PaymentInput input) {
        CareContext.Admission admission = context.admission(admissionId)
                .orElseThrow(() -> ApiException.notFound("Admission", admissionId));
        if (!admission.isActive()) {
            throw ApiException.conflict("ADMISSION_CLOSED", "Admission " + admission.admissionNumber() + " is "
                    + admission.status().toLowerCase() + "; settle its bill instead");
        }
        bills.lockLiveIpdBill(admissionId).filter(b -> "FINAL".equals(b.status())).ifPresent(b -> {
            throw ApiException.conflict("BILL_FINAL", "The bill is already final; record a settlement on " + b.billNumber());
        });
        if (input.amount() == null) {
            throw ApiException.unprocessable("AMOUNT_REQUIRED", "Enter the advance amount");
        }
        requireReference(input);
        PaymentRepository.Payment payment = payments.insert(new PaymentRepository.NewPayment(
                admission.patientId(), null, admissionId, "ADVANCE", input.mode().name(), input.amount(),
                blankToNull(input.reference()), input.cardLast4(), currentUser.subject(), blankToNull(input.remarks())));
        audit.record(Action.CREATE, "payment", payment.receiptNumber(), admission.patientId(),
                Map.of("type", "ADVANCE", "mode", input.mode().name(), "admission", admission.admissionNumber()));
        return payment;
    }

    @Transactional
    public PaymentRepository.Payment voidPayment(UUID paymentId, String reason) {
        PaymentRepository.Payment payment = payments.find(paymentId)
                .orElseThrow(() -> ApiException.notFound("Receipt", paymentId));
        if (!"COMPLETED".equals(payment.status())) {
            throw ApiException.conflict("ALREADY_VOIDED", "Receipt " + payment.receiptNumber() + " is already void");
        }
        payments.voidPayment(paymentId, time.now(), currentUser.subject(), reason.trim());
        audit.record(Action.UPDATE, "payment", payment.receiptNumber(), payment.patientId(),
                Map.of("action", "void"));
        return payments.find(paymentId).orElseThrow();
    }

    public BigDecimal unallocatedAdvance(UUID admissionId) {
        return payments.unallocatedAdvance(admissionId);
    }

    private static void requireReference(PaymentInput input) {
        if (input.mode() != PaymentMode.CASH && (input.reference() == null || input.reference().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REFERENCE_REQUIRED",
                    "Enter the transaction reference for " + input.mode().name().replace('_', ' ').toLowerCase()
                            + " payments");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
