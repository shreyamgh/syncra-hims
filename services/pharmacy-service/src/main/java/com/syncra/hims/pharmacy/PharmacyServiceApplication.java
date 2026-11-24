package com.syncra.hims.pharmacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Syncra HIMS Pharmacy Service: stock by batch and expiry in the main store and the OPD
 * pharmacy, goods receipts from suppliers, requisitions between stores, FEFO dispensing against
 * issued prescriptions, expiry write-offs, reorder and near-expiry alerts, and the Schedule H1
 * register. Billing of what is dispensed is done by the Billing Service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PharmacyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharmacyServiceApplication.class, args);
    }
}
