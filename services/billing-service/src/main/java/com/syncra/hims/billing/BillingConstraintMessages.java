package com.syncra.hims.billing;

import com.syncra.hims.common.web.ConstraintMessages;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BillingConstraintMessages implements ConstraintMessages {

    @Override
    public Map<String, String> messages() {
        return Map.of(
                "bill_one_live_ipd_bill", "This admission already has an open or final IPD bill",
                "tariff_rate_no_overlap", "Another rate for this service and ward class is valid in that period",
                "payment_receipt_number_key", "Receipt numbering conflict, please retry");
    }
}
