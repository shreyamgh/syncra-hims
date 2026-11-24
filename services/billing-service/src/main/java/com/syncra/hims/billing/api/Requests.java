package com.syncra.hims.billing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the billing API. */
public final class Requests {

    private Requests() {
    }

    public enum PaymentMode {
        CASH, CARD, UPI, NET_BANKING, CHEQUE, DEMAND_DRAFT, INSURANCE, GOVT_SCHEME, CORPORATE_CREDIT
    }

    public enum SettlementType { SETTLEMENT, REFUND }

    /**
     * Money received or paid out. {@code amount} may be omitted on a settlement to take
     * the full balance due. Non-cash modes need a reference (UPI RRN, card approval
     * code, cheque number…). Never send full card numbers — only the last four digits.
     */
    public record PaymentInput(
            @NotNull PaymentMode mode,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 64) String reference,
            @Pattern(regexp = "^\\d{4}$") String cardLast4,
            @Size(max = 300) String remarks) {
    }

    /** An extra chargeable service on an OPD slip (e.g. a lab test ordered at the desk). */
    public record ExtraItem(
            @NotBlank @Size(max = 16) String serviceCode,
            @NotNull @DecimalMin("0.01") @DecimalMax("999") @Digits(integer = 3, fraction = 2) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount) {
    }

    /** Issue the OPD slip for a token: registration (when due), consultation, extras; optionally pay. */
    public record OpdSlipRequest(
            @NotNull UUID visitId,
            @Size(max = 16) String tariffPlanCode,
            @Valid @Size(max = 20) List<ExtraItem> items,
            @Valid PaymentInput payment) {

        public List<ExtraItem> itemList() {
            return items == null ? List.of() : items;
        }
    }

    /** A service added to an open IPD bill by the billing desk (procedures, consultant visits…). */
    public record AddLineRequest(
            @NotBlank @Size(max = 16) String serviceCode,
            @NotNull @DecimalMin("0.01") @DecimalMax("999") @Digits(integer = 3, fraction = 2) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
            LocalDate serviceDate) {
    }

    public record SettlementRequest(@NotNull SettlementType type, @Valid @NotNull PaymentInput payment) {
    }

    public record AdvanceRequest(@Valid @NotNull PaymentInput payment) {
    }

    public record PharmacySaleRequest(@NotNull UUID dispenseId, @Valid PaymentInput payment) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
