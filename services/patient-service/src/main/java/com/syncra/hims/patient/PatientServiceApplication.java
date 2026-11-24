package com.syncra.hims.patient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Syncra HIMS Patient Service.
 *
 * <ul>
 *   <li>Registration and the master patient index; UHIDs ({@code SYNC-YYYYMMDD-XXXX})
 *       are issued gap-free by the database function {@code patient.next_uhid()}</li>
 *   <li>OPD: per-department daily token queue with a guarded state machine</li>
 *   <li>IPD: admissions, bed assignment and transfers across the 50-bed layout,
 *       with double-booking prevented by database exclusion constraints</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PatientServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatientServiceApplication.class, args);
    }
}
