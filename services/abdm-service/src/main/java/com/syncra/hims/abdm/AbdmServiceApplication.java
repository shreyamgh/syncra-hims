package com.syncra.hims.abdm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Syncra HIMS ABDM Service: the hospital's Ayushman Bharat Digital Mission bridge.
 *
 * <ul>
 *   <li>Milestone 1 — ABHA verification by OTP and Scan &amp; Share at the registration desk.</li>
 *   <li>Milestone 2 (HIP) — care contexts per encounter linked to the patient's ABHA, consent
 *       notifications, and on request FHIR R4 records (OP consultation, prescription, discharge
 *       summary) encrypted with Fidelius (ECDH Curve25519, AES-GCM) and pushed to the HIU.</li>
 * </ul>
 * Staff call {@code /api/v1/abdm/**} with a Syncra JWT; ABDM calls {@code /api/v3/**} with a JWT
 * signed by the ABDM gateway.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AbdmServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AbdmServiceApplication.class, args);
    }
}
