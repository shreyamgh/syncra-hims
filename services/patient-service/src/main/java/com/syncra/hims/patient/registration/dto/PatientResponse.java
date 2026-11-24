package com.syncra.hims.patient.registration.dto;

import com.syncra.hims.patient.registration.AbhaNumber;
import com.syncra.hims.patient.registration.Demographics;
import com.syncra.hims.patient.registration.Gender;
import com.syncra.hims.patient.registration.MaritalStatus;
import com.syncra.hims.patient.registration.Patient;
import com.syncra.hims.patient.registration.RegistrationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Full patient record (authorised staff only; every read is audited). */
public record PatientResponse(
        UUID id,
        String uhid,
        String title,
        String firstName,
        String middleName,
        String lastName,
        String fullName,
        Gender gender,
        LocalDate dateOfBirth,
        boolean dobEstimated,
        Integer ageYears,
        String bloodGroup,
        MaritalStatus maritalStatus,
        String relationType,
        String relationName,
        String mobile,
        String alternateMobile,
        String email,
        PatientRequest.Address address,
        String abhaNumber,
        String abhaAddress,
        boolean abhaVerified,
        PatientRequest.EmergencyContact emergencyContact,
        RegistrationSource registrationSource,
        boolean confidential,
        boolean deceased,
        Instant deceasedAt,
        Instant registeredAt,
        long version) {

    public static PatientResponse from(Patient p, Integer ageYears) {
        Demographics d = p.demographics();
        return new PatientResponse(
                p.getId(), p.getUhid().value(), d.title(), d.firstName(), d.middleName(), d.lastName(),
                p.getFullName(), d.gender(), d.dateOfBirth(), d.dobEstimated(), ageYears, d.bloodGroup(),
                d.maritalStatus(), d.relationType(), d.relationName(), d.mobile(), d.alternateMobile(), d.email(),
                new PatientRequest.Address(d.addressLine(), d.cityVillage(), d.districtName(), d.districtLgdCode(),
                        d.stateName(), d.stateLgdCode(), d.pincode()),
                AbhaNumber.format(d.abhaNumber()), d.abhaAddress(), p.getAbhaVerifiedAt() != null,
                new PatientRequest.EmergencyContact(d.emergencyContactName(), d.emergencyContactMobile()),
                p.getRegistrationSource(), d.confidential(), p.isDeceased(), p.getDeceasedAt(),
                p.getCreatedAt(), p.getVersion());
    }
}
