package com.syncra.hims.billing.api;

import com.syncra.hims.billing.Roles;
import com.syncra.hims.billing.api.Requests.AddLineRequest;
import com.syncra.hims.billing.api.Requests.AdvanceRequest;
import com.syncra.hims.billing.api.Requests.OpdSlipRequest;
import com.syncra.hims.billing.api.Requests.ReasonRequest;
import com.syncra.hims.billing.api.Requests.SettlementRequest;
import com.syncra.hims.billing.bill.BillingService;
import com.syncra.hims.billing.bill.IpdBillingService;
import com.syncra.hims.billing.bill.OpdBillingService;
import com.syncra.hims.billing.payment.PaymentRepository;
import com.syncra.hims.billing.payment.PaymentService;
import com.syncra.hims.billing.print.BillPrinter;
import com.syncra.hims.billing.tariff.Plan;
import com.syncra.hims.billing.tariff.Rate;
import com.syncra.hims.billing.tariff.TariffService;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Billing API: {@code /api/v1/billing}. */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    public record ReceiptView(UUID paymentId, String receiptNumber, String type, String mode, BigDecimal amount,
                              String status, BigDecimal unallocatedAdvance) {
    }

    private final TariffService tariffs;
    private final OpdBillingService opd;
    private final com.syncra.hims.billing.bill.PharmacyBillingService pharmacy;
    private final IpdBillingService ipd;
    private final BillingService billing;
    private final PaymentService payments;
    private final BillPrinter printer;
    private final AuditService audit;
    private final HospitalTime time;

    public BillingController(TariffService tariffs, OpdBillingService opd, IpdBillingService ipd,
                             BillingService billing, PaymentService payments, BillPrinter printer, AuditService audit,
                             HospitalTime time, com.syncra.hims.billing.bill.PharmacyBillingService pharmacy) {
        this.pharmacy = pharmacy;
        this.tariffs = tariffs;
        this.opd = opd;
        this.ipd = ipd;
        this.billing = billing;
        this.payments = payments;
        this.printer = printer;
        this.audit = audit;
        this.time = time;
    }

    // ------------------------------------------------------------------ tariffs

    @GetMapping("/tariffs")
    @PreAuthorize(Roles.VIEW)
    public List<Plan> plans() {
        return tariffs.plans();
    }

    /** Rate card of a plan for one ward class (ALL for OPD), valid on {@code date} (default today). */
    @GetMapping("/tariffs/{planCode}/rates")
    @PreAuthorize(Roles.VIEW)
    public List<Rate> rateCard(@PathVariable String planCode,
                               @RequestParam(defaultValue = "ALL") String bedCategory,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return tariffs.rateCard(tariffs.requirePlan(planCode).id(), bedCategory.toUpperCase(),
                date == null ? time.today() : date);
    }

    // ------------------------------------------------------------------ OPD

    /** Issues (and optionally collects) the OPD slip for a token. */
    @PostMapping("/opd-slips")
    @PreAuthorize(Roles.OPD_BILLING)
    public ResponseEntity<BillView> issueOpdSlip(@Valid @RequestBody OpdSlipRequest request) {
        BillView bill = opd.issueSlip(request);
        return ResponseEntity.created(URI.create("/api/v1/billing/bills/" + bill.billId())).body(bill);
    }

    // ------------------------------------------------------------------ IPD

    /** The pharmacy's GST invoice for a dispense (and the payment taken at the counter). */
    @PostMapping("/pharmacy-sales")
    @PreAuthorize(Roles.PHARMACY_SALES)
    public ResponseEntity<BillView> pharmacySale(@Valid @RequestBody Requests.PharmacySaleRequest request) {
        BillView bill = pharmacy.sell(request.dispenseId(), request.payment());
        return ResponseEntity.created(URI.create("/api/v1/billing/bills/" + bill.billId())).body(bill);
    }

    @GetMapping("/admissions/{admissionId}/bill")
    @PreAuthorize(Roles.VIEW)
    public BillView currentIpdBill(@PathVariable UUID admissionId) {
        BillView bill = ipd.current(admissionId);
        audit.record(Action.READ, "bill", bill.billId(), null);
        return bill;
    }

    /** Recalculates the interim bill (bed-days, nursing, orders) up to now. */
    @PostMapping("/admissions/{admissionId}/bill/refresh")
    @PreAuthorize(Roles.IPD_BILLING)
    public BillView refreshIpdBill(@PathVariable UUID admissionId) {
        return ipd.refresh(admissionId);
    }

    /** Issues the final discharge bill (discharge must have been initiated). */
    @PostMapping("/admissions/{admissionId}/bill/finalize")
    @PreAuthorize(Roles.IPD_BILLING)
    public BillView finalizeIpdBill(@PathVariable UUID admissionId) {
        return ipd.finalizeBill(admissionId);
    }

    @PostMapping("/admissions/{admissionId}/advances")
    @PreAuthorize(Roles.CASHIER)
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView takeAdvance(@PathVariable UUID admissionId, @Valid @RequestBody AdvanceRequest request) {
        PaymentRepository.Payment p = payments.advance(admissionId, request.payment());
        return new ReceiptView(p.id(), p.receiptNumber(), p.type(), p.mode(), p.amount(), p.status(),
                payments.unallocatedAdvance(admissionId));
    }

    // ------------------------------------------------------------------ bills

    @GetMapping("/bills/{billId}")
    @PreAuthorize(Roles.VIEW)
    public BillView bill(@PathVariable UUID billId) {
        BillView bill = billing.view(billId);
        audit.record(Action.READ, "bill", billId, null);
        return bill;
    }

    @GetMapping("/bills")
    @PreAuthorize(Roles.VIEW)
    public List<BillView.Summary> billsForPatient(@RequestParam String uhid) {
        List<BillView.Summary> bills = billing.forPatient(uhid);
        audit.record(Action.READ, "bill_list", null, null, Map.of("results", bills.size()));
        return bills;
    }

    /** Print-ready HTML (use the browser's print dialog). */
    @GetMapping(value = "/bills/{billId}/print", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize(Roles.VIEW)
    public ResponseEntity<String> print(@PathVariable UUID billId) {
        BillView bill = billing.view(billId);
        audit.record(Action.PRINT, "bill", billId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(printer.render(bill));
    }

    @PostMapping("/bills/{billId}/lines")
    @PreAuthorize(Roles.IPD_BILLING)
    public BillView addLine(@PathVariable UUID billId, @Valid @RequestBody AddLineRequest request) {
        return ipd.addLine(billId, request);
    }

    @DeleteMapping("/bills/{billId}/lines/{lineId}")
    @PreAuthorize(Roles.IPD_BILLING)
    public BillView removeLine(@PathVariable UUID billId, @PathVariable UUID lineId) {
        return ipd.removeLine(billId, lineId);
    }

    /** Settlement of the balance due, or refund of a credit, on a final bill. */
    @PostMapping("/bills/{billId}/payments")
    @PreAuthorize(Roles.CASHIER)
    @ResponseStatus(HttpStatus.CREATED)
    public BillView pay(@PathVariable UUID billId, @Valid @RequestBody SettlementRequest request) {
        return payments.settle(billId, request.type(), request.payment());
    }

    @PostMapping("/bills/{billId}/cancel")
    @PreAuthorize(Roles.SUPERVISOR)
    public BillView cancel(@PathVariable UUID billId, @Valid @RequestBody ReasonRequest request) {
        return billing.cancel(billId, request.reason());
    }

    @PostMapping("/payments/{paymentId}/void")
    @PreAuthorize(Roles.SUPERVISOR)
    public ReceiptView voidPayment(@PathVariable UUID paymentId, @Valid @RequestBody ReasonRequest request) {
        PaymentRepository.Payment p = payments.voidPayment(paymentId, request.reason());
        return new ReceiptView(p.id(), p.receiptNumber(), p.type(), p.mode(), p.amount(), p.status(), null);
    }
}
