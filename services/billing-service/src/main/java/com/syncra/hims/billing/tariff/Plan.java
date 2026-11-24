package com.syncra.hims.billing.tariff;

import java.util.UUID;

public record Plan(UUID id, String code, String name, String payerType, boolean isDefault) {
}
