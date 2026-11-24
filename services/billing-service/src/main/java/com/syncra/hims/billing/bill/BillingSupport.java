package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.Roles;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Rules shared by OPD and IPD billing: discount approval, rounding, labels. */
@Component
public class BillingSupport {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final CurrentUser currentUser;

    public BillingSupport(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * Discounts are approved by the person granting them, who must be an
     * administrator; returns the approver to record on the line, or null if none.
     */
    public String discountApprover(BigDecimal discount) {
        if (discount == null || discount.signum() == 0) {
            return null;
        }
        if (!currentUser.hasRole(Roles.DISCOUNT_APPROVER_ROLE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DISCOUNT_NOT_PERMITTED",
                    "Only an administrator can grant a discount");
        }
        return currentUser.subject();
    }

    public String actor() {
        return currentUser.subject();
    }

    /** Amount that brings the net to the nearest whole rupee (half rounds up). */
    public static BigDecimal roundOff(BigDecimal net) {
        return net.setScale(0, RoundingMode.HALF_UP).subtract(net);
    }

    public static String wardClass(String bedCategory) {
        return switch (bedCategory) {
            case "SEMI_PRIVATE" -> "Semi-private";
            case "PRIVATE" -> "Private";
            case "ICU" -> "ICU";
            default -> "General";
        };
    }

    public static String dayRange(LocalDate first, LocalDate last) {
        return first.equals(last) ? DAY.format(first) : DAY.format(first) + " – " + DAY.format(last);
    }
}
