package com.syncra.hims.pharmacy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param nearExpiryDays batches expiring within this many days are flagged
 * @param pendingDays    issued prescriptions of the last this many days are offered for dispensing
 */
@Validated
@ConfigurationProperties("syncra.pharmacy")
public record PharmacyProperties(@Min(7) @Max(365) @DefaultValue("90") int nearExpiryDays,
                                 @Min(1) @Max(90) @DefaultValue("7") int pendingDays) {
}
