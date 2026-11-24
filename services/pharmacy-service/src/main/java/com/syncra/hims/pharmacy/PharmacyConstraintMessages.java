package com.syncra.hims.pharmacy;

import com.syncra.hims.common.web.ConstraintMessages;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PharmacyConstraintMessages implements ConstraintMessages {

    @Override
    public Map<String, String> messages() {
        return Map.of(
                "goods_receipt_invoice_once", "This supplier invoice has already been received",
                "stock_quantity_check", "Not enough stock in this store",
                "batch_cost_within_mrp", "The purchase rate cannot be above the MRP",
                "requisition_drug_once", "A medicine appears twice in the requisition");
    }
}
