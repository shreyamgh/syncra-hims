package com.syncra.hims.patient.registration.dto;

import com.syncra.hims.patient.registration.Gender;
import com.syncra.hims.patient.registration.PatientSearch;
import java.time.LocalDate;

/**
 * Search-result row. The mobile number is masked to its last four digits
 * (privacy by default); the full record is fetched — and audited — separately.
 */
public record PatientSummary(
        String uhid,
        String fullName,
        Gender gender,
        Integer ageYears,
        String maskedMobile,
        boolean abhaLinked,
        boolean deceased,
        LocalDate registeredOn) {

    public static PatientSummary from(PatientSearch.Row row, Integer ageYears, LocalDate registeredOn) {
        return new PatientSummary(row.uhid(), row.fullName(), row.gender(), ageYears, mask(row.mobile()),
                row.abhaLinked(), row.deceased(), registeredOn);
    }

    static String mask(String mobile) {
        return mobile == null || mobile.length() < 4 ? null : "******" + mobile.substring(mobile.length() - 4);
    }
}
