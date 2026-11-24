package com.syncra.hims.billing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Hospital-specific billing settings. */
@Validated
@ConfigurationProperties(prefix = "syncra.billing")
public record BillingProperties(
        @Min(0) @Max(3650) int registrationValidityDays,
        @NotNull Hospital hospital) {

    /** Letterhead printed on bills. GSTIN is required on GST invoices. */
    public record Hospital(String name, String address, String phone, String gstin) {

        public static final String NOT_CONFIGURED = "Hospital name not configured (set SYNCRA_HOSPITAL_NAME)";

        /** A bill must never print without a hospital name: an unset name is shown as such. */
        public Hospital {
            if (name == null || name.isBlank()) {
                name = NOT_CONFIGURED;
            }
        }
    }
}
