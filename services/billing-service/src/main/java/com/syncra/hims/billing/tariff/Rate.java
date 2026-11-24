package com.syncra.hims.billing.tariff;

import java.math.BigDecimal;
import java.util.UUID;

/** The effective price of one service under one plan, for a ward class, on a date. */
public record Rate(UUID serviceId, String serviceCode, String serviceName, String category, String sacCode,
                   String chargeUnit, UUID tariffRateId, BigDecimal amount, BigDecimal gstRate) {
}
