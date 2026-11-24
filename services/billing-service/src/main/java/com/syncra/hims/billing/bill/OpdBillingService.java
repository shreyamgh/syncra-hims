package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.BillingProperties;
import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.billing.api.Requests.ExtraItem;
import com.syncra.hims.billing.api.Requests.OpdSlipRequest;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OPD slips: one bill per token, issued (numbered) immediately at the counter.
 *
 * <ul>
 *   <li>Registration fee only when the patient has no paid registration within the
 *       validity period ({@code syncra.billing.registration-validity-days}).</li>
 *   <li>Consultation by visit type: new / referral, follow-up, or emergency.</li>
 *   <li>Investigation orders already placed for the encounter, plus desk extras.</li>
 * </ul>
 * A visit can only be charged once (unless its slip is cancelled).
 */
@Service
public class OpdBillingService {

    private final CareContext context;
    private final BillRepository bills;
    private final TariffService tariffs;
    private final PaymentService payments;
    private final BillViews views;
    private final BillingSupport support;
    private final BillingProperties properties;
    private final AuditService audit;
    private final HospitalTime time;

    public OpdBillingService(CareContext context, BillRepository bills, TariffService tariffs, PaymentService payments,
                             BillViews views, BillingSupport support, BillingProperties properties, AuditService audit,
                             HospitalTime time) {
        this.context = context;
        this.bills = bills;
        this.tariffs = tariffs;
        this.payments = payments;
        this.views = views;
        this.support = support;
        this.properties = properties;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public BillView issueSlip(OpdSlipRequest request) {
        CareContext.Visit visit = context.visit(request.visitId())
                .orElseThrow(() -> ApiException.notFound("OPD visit", request.visitId()));
        if ("CANCELLED".equals(visit.queueStatus())) {
            throw ApiException.unprocessable("VISIT_CANCELLED", "Token " + visit.tokenNumber() + " was cancelled");
        }
        bills.billForVisit(visit.visitId()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_BILLED",
                    "This OPD visit is already billed on " + existing, Map.of("billNumber", existing));
        });

        Plan plan = tariffs.requirePlan(request.tariffPlanCode());
        boolean emergency = "EMERGENCY".equals(visit.encounterType()) || "EMERGENCY".equals(visit.visitType());
        String actor = support.actor();
        UUID billId = bills.create(emergency ? "EMERGENCY" : "OPD", visit.patientId(), visit.encounterId(), null,
                plan.id(), "DRAFT", actor);
        LocalDate day = visit.visitDate();

        LocalDate validFrom = day.minusDays(properties.registrationValidityDays());
        if (!context.registrationPaidSince(visit.patientId(), validFrom)) {
            Rate registration = tariffs.requireRate(plan.id(), "REG-OPD", TariffService.ALL, day);
            bills.addLine(billId, registration, registration.serviceName(), day, BigDecimal.ONE, BigDecimal.ZERO,
                    null, "OPD_VISIT", visit.visitId(), actor);
        }

        String consultation = emergency ? "CON-EMR" : "FOLLOW_UP".equals(visit.visitType()) ? "CON-FUP" : "CON-NEW";
        Rate consult = tariffs.requireRate(plan.id(), consultation, TariffService.ALL, day);
        bills.addLine(billId, consult, consult.serviceName() + " — " + visit.departmentName(), day, BigDecimal.ONE,
                BigDecimal.ZERO, null, "OPD_VISIT", visit.visitId(), actor);

        for (CareContext.Order order : context.unbilledOrders(visit.encounterId())) {
            Rate rate = tariffs.requireRate(plan.id(), order.serviceCode(), TariffService.ALL, time.dateOf(order.orderedAt()));
            bills.addLine(billId, rate, rate.serviceName(), time.dateOf(order.orderedAt()), BigDecimal.ONE,
                    BigDecimal.ZERO, null, "SERVICE_ORDER", order.orderId(), actor);
        }
        for (ExtraItem item : request.itemList()) {
            String code = item.serviceCode().trim().toUpperCase();
            requireNotAutomatic(tariffs, code);
            Rate rate = tariffs.requireRate(plan.id(), code, TariffService.ALL, day);
            bills.addLine(billId, rate, rate.serviceName(), day, item.quantity(), item.discountAmount(),
                    support.discountApprover(item.discountAmount()), null, null, actor);
        }

        BillRepository.Header draft = bills.lock(billId).orElseThrow();
        bills.finalizeBill(billId, BillingSupport.roundOff(draft.netAmount()), actor);
        BillRepository.Header issued = bills.find(billId).orElseThrow();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("number", issued.billNumber());
        details.put("visit", visit.visitId().toString());
        audit.record(Action.CREATE, "bill", billId, visit.patientId(), details);

        if (request.payment() != null) {
            payments.record(issued, SettlementType.SETTLEMENT, request.payment());
        }
        return views.view(billId);
    }

    /** Admission, bed and nursing charges come from the engine only; 404 for unknown services. */
    static void requireNotAutomatic(TariffService tariffs, String serviceCode) {
        String category = tariffs.category(serviceCode).orElseThrow(() -> ApiException.notFound("Service", serviceCode));
        if (BillRepository.ENGINE_CATEGORIES.contains(category)) {
            throw ApiException.unprocessable("AUTOMATIC_CHARGE",
                    serviceCode + " is calculated automatically from the admission and bed stays");
        }
    }
}
