package com.syncra.hims.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Syncra HIMS Billing Service: tariff resolution, OPD slips, IPD interim and final
 * bills (bed-days by midnight census), advances, receipts, refunds and printable
 * GST-compliant bills. Arithmetic (line totals, bill totals) and immutability of
 * issued bills and receipts are enforced by the database itself.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
