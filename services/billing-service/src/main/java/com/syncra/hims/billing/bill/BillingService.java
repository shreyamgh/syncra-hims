package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.api.BillView;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bill look-up and cancellation. */
@Service
public class BillingService {

    private final BillRepository bills;
    private final BillViews views;
    private final BillingSupport support;
    private final AuditService audit;
    private final HospitalTime time;
    private final JdbcClient jdbc;

    public BillingService(BillRepository bills, BillViews views, BillingSupport support, AuditService audit,
                          HospitalTime time, JdbcClient jdbc) {
        this.bills = bills;
        this.views = views;
        this.support = support;
        this.audit = audit;
        this.time = time;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BillView view(UUID billId) {
        return views.view(billId);
    }

    @Transactional(readOnly = true)
    public List<BillView.Summary> forPatient(String uhid) {
        UUID patientId = jdbc.sql("SELECT id FROM patient.patient WHERE uhid = :uhid")
                .param("uhid", uhid.trim().toUpperCase()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Patient", uhid));
        return bills.forPatient(patientId).stream()
                .map(h -> new BillView.Summary(h.id(), h.billNumber(), h.billType(), h.status(), h.payableAmount(),
                        h.createdAt()))
                .toList();
    }

    /**
     * Cancels a bill (the serial number stays used, as GST requires). Money already
     * taken against it must be refunded or voided first.
     */
    @Transactional
    public BillView cancel(UUID billId, String reason) {
        BillRepository.Header bill = bills.lock(billId).orElseThrow(() -> ApiException.notFound("Bill", billId));
        if ("CANCELLED".equals(bill.status())) {
            throw ApiException.conflict("ALREADY_CANCELLED", "The bill is already cancelled");
        }
        BillRepository.Balance balance = bills.balance(billId);
        if (balance.received().subtract(balance.refunded()).signum() != 0) {
            throw ApiException.conflict("PAYMENTS_EXIST",
                    "Rs " + balance.received().subtract(balance.refunded()).toPlainString()
                            + " has been received against this bill; refund or void the receipts first");
        }
        bills.cancel(billId, time.now(), support.actor(), reason.trim());
        audit.record(Action.UPDATE, "bill", billId, bill.patientId(), Map.of("action", "cancel"));
        return views.view(billId);
    }
}
