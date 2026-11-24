package com.syncra.hims.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A bill as shown at the counter and printed. */
public record BillView(
        UUID billId,
        String billNumber,
        String title,
        String billType,
        String status,
        Patient patient,
        String tariffPlan,
        UUID encounterId,
        UUID admissionId,
        String admissionNumber,
        List<Line> lines,
        Totals totals,
        List<Receipt> receipts,
        BigDecimal unallocatedAdvance,
        Instant createdAt,
        Instant finalizedAt,
        String finalizedBy,
        Instant cancelledAt,
        String cancellationReason) {

    public record Patient(String uhid, String name, String gender, Integer ageYears, String address) {
    }

    public record Line(UUID lineId, String serviceCode, String category, String sacCode, String description,
                       LocalDate serviceDate, BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossAmount,
                       BigDecimal discountAmount, BigDecimal gstRate, BigDecimal taxAmount, BigDecimal lineTotal,
                       boolean automatic) {
    }

    public record Totals(BigDecimal gross, BigDecimal discount, BigDecimal tax, BigDecimal net, BigDecimal roundOff,
                         BigDecimal payable, BigDecimal received, BigDecimal refunded, BigDecimal balanceDue) {
    }

    public record Receipt(UUID paymentId, String receiptNumber, String type, String mode, BigDecimal amount,
                          String reference, Instant receivedAt, String receivedBy, String status) {
    }

    /** Compact row for bill lists. */
    public record Summary(UUID billId, String billNumber, String billType, String status, BigDecimal payable,
                          Instant createdAt) {
    }
}
