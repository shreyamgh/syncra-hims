package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.billing.api.Requests.AddLineRequest;
import com.syncra.hims.billing.bill.BedDayCalculator.Charge;
import com.syncra.hims.billing.payment.PaymentRepository;
import com.syncra.hims.billing.tariff.Rate;
import com.syncra.hims.billing.tariff.TariffService;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The IPD bill: one per admission, kept INTERIM (unnumbered) while the patient is
 * in hospital and issued FINAL at discharge.
 *
 * <p>Admission, bed and nursing charges are owned by the engine and regenerated on
 * every refresh from the admission and its bed stays (midnight census, priced at
 * each bed's ward class). Investigation orders are added once each. Everything
 * else (procedures, consultant visits, consumables) is added by the billing desk
 * and survives refreshes. Finalising sets off the patient's advances.
 */
@Service
public class IpdBillingService {

    private final CareContext context;
    private final BillRepository bills;
    private final PaymentRepository payments;
    private final TariffService tariffs;
    private final BillViews views;
    private final BillingSupport support;
    private final AuditService audit;
    private final HospitalTime time;

    public IpdBillingService(CareContext context, BillRepository bills, PaymentRepository payments,
                             TariffService tariffs, BillViews views, BillingSupport support, AuditService audit,
                             HospitalTime time) {
        this.context = context;
        this.bills = bills;
        this.payments = payments;
        this.tariffs = tariffs;
        this.views = views;
        this.support = support;
        this.audit = audit;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public BillView current(UUID admissionId) {
        BillRepository.Header bill = bills.liveIpdBill(admissionId)
                .orElseThrow(() -> ApiException.notFound("IPD bill for admission", admissionId));
        return views.view(bill.id());
    }

    /** Recalculates automatic charges up to now (or the discharge time) and returns the interim bill. */
    @Transactional
    public BillView refresh(UUID admissionId) {
        CareContext.Admission admission = requireAdmission(admissionId);
        BillRepository.Header bill = openBill(admission);
        if ("FINAL".equals(bill.status())) {
            throw ApiException.conflict("BILL_FINAL", "Bill " + bill.billNumber() + " is already final");
        }
        regenerate(bill, admission);
        audit.record(Action.UPDATE, "bill", bill.id(), admission.patientId(), Map.of("action", "refresh"));
        return views.view(bill.id());
    }

    /**
     * Issues the final bill once discharge has been initiated (or the stay has ended):
     * recalculates charges to the discharge time, sets off advances, rounds to the
     * rupee and lets the database assign the serial number.
     */
    @Transactional
    public BillView finalizeBill(UUID admissionId) {
        CareContext.Admission admission = requireAdmission(admissionId);
        if ("ADMITTED".equals(admission.status())) {
            throw ApiException.unprocessable("DISCHARGE_NOT_INITIATED",
                    "Initiate the discharge of " + admission.admissionNumber() + " before finalising its bill");
        }
        BillRepository.Header bill = openBill(admission);
        if ("FINAL".equals(bill.status())) {
            throw ApiException.conflict("BILL_FINAL", "Bill " + bill.billNumber() + " is already final");
        }
        regenerate(bill, admission);
        int advances = payments.allocateAdvances(admissionId, bill.id());
        BillRepository.Header totals = bills.lock(bill.id()).orElseThrow();
        bills.finalizeBill(bill.id(), BillingSupport.roundOff(totals.netAmount()), support.actor());
        BillRepository.Header issued = bills.find(bill.id()).orElseThrow();
        audit.record(Action.UPDATE, "bill", bill.id(), admission.patientId(),
                Map.of("action", "finalise", "number", issued.billNumber(), "advancesSetOff", advances));
        return views.view(bill.id());
    }

    /** Adds a desk-entered service to the open bill, priced at the patient's current ward class. */
    @Transactional
    public BillView addLine(UUID billId, AddLineRequest request) {
        BillRepository.Header bill = requireOpenBill(billId);
        String category = bill.admissionId() == null ? TariffService.ALL
                : context.currentBedCategory(bill.admissionId()).orElse(TariffService.ALL);
        LocalDate day = request.serviceDate() == null ? time.today() : request.serviceDate();
        String code = request.serviceCode().trim().toUpperCase();
        OpdBillingService.requireNotAutomatic(tariffs, code);
        Rate rate = tariffs.requireRate(bill.tariffPlanId(), code, category, day);
        bills.addLine(billId, rate, rate.serviceName(), day, request.quantity(), request.discountAmount(),
                support.discountApprover(request.discountAmount()), null, null, support.actor());
        audit.record(Action.UPDATE, "bill", billId, bill.patientId(), Map.of("action", "add_line", "service", rate.serviceCode()));
        return views.view(billId);
    }

    @Transactional
    public BillView removeLine(UUID billId, UUID lineId) {
        BillRepository.Header bill = requireOpenBill(billId);
        String category = bills.lineCategory(billId, lineId).orElseThrow(() -> ApiException.notFound("Bill line", lineId));
        if (BillRepository.ENGINE_CATEGORIES.contains(category)) {
            throw ApiException.unprocessable("AUTOMATIC_CHARGE",
                    "Admission, bed and nursing charges follow the bed stays and cannot be removed by hand");
        }
        bills.deleteLine(billId, lineId);
        audit.record(Action.UPDATE, "bill", billId, bill.patientId(), Map.of("action", "remove_line"));
        return views.view(billId);
    }

    private void regenerate(BillRepository.Header bill, CareContext.Admission admission) {
        String actor = support.actor();
        Instant end = admission.dischargedAt() != null ? admission.dischargedAt() : time.now();
        bills.deleteEngineLines(bill.id());

        LocalDate admitted = time.dateOf(admission.admittedAt());
        Rate admissionCharge = tariffs.requireRate(bill.tariffPlanId(), "ADM-CHG", TariffService.ALL, admitted);
        bills.addLine(bill.id(), admissionCharge, admissionCharge.serviceName(), admitted, BigDecimal.ONE,
                BigDecimal.ZERO, null, null, null, actor);

        for (Charge charge : BedDayCalculator.calculate(context.stays(admission.admissionId()), end, time.zone())) {
            if (charge.days() == 0) {
                continue;
            }
            String ward = BillingSupport.wardClass(charge.bedCategory());
            String period = BillingSupport.dayRange(charge.firstDay(), charge.lastDay());
            BigDecimal days = BigDecimal.valueOf(charge.days());
            Rate bed = tariffs.requireRate(bill.tariffPlanId(), "BED-DAY", charge.bedCategory(), charge.firstDay());
            bills.addLine(bill.id(), bed, "Bed charges — " + charge.bedCode() + ", " + ward + " (" + period + ")",
                    charge.firstDay(), days, BigDecimal.ZERO, null, "BED_ASSIGNMENT", charge.assignmentId(), actor);
            Rate nursing = tariffs.requireRate(bill.tariffPlanId(), "NUR-DAY", charge.bedCategory(), charge.firstDay());
            bills.addLine(bill.id(), nursing, "Nursing charges — " + ward + " (" + period + ")", charge.firstDay(), days,
                    BigDecimal.ZERO, null, "BED_ASSIGNMENT", charge.assignmentId(), actor);
        }

        String category = context.currentBedCategory(admission.admissionId()).orElse(TariffService.ALL);
        for (CareContext.Order order : context.unbilledOrders(admission.encounterId())) {
            LocalDate day = time.dateOf(order.orderedAt());
            Rate rate = tariffs.requireRate(bill.tariffPlanId(), order.serviceCode(), category, day);
            bills.addLine(bill.id(), rate, rate.serviceName(), day, BigDecimal.ONE, BigDecimal.ZERO, null,
                    "SERVICE_ORDER", order.orderId(), actor);
        }
    }

    private BillRepository.Header openBill(CareContext.Admission admission) {
        return bills.lockLiveIpdBill(admission.admissionId()).orElseGet(() -> {
            UUID id = bills.create("IPD", admission.patientId(), admission.encounterId(), admission.admissionId(),
                    admission.tariffPlanId(), "INTERIM", support.actor());
            return bills.lock(id).orElseThrow();
        });
    }

    private BillRepository.Header requireOpenBill(UUID billId) {
        BillRepository.Header bill = bills.lock(billId).orElseThrow(() -> ApiException.notFound("Bill", billId));
        if (!"DRAFT".equals(bill.status()) && !"INTERIM".equals(bill.status())) {
            throw ApiException.conflict("BILL_CLOSED", "Bill " + bill.billNumber() + " is " + bill.status().toLowerCase()
                    + "; its lines can no longer change");
        }
        return bill;
    }

    private CareContext.Admission requireAdmission(UUID admissionId) {
        CareContext.Admission admission = context.admission(admissionId)
                .orElseThrow(() -> ApiException.notFound("Admission", admissionId));
        if ("CANCELLED".equals(admission.status())) {
            throw ApiException.unprocessable("ADMISSION_CANCELLED", "Admission " + admission.admissionNumber()
                    + " was cancelled and is not billable");
        }
        return admission;
    }
}
