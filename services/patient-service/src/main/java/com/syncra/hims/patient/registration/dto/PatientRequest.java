package com.syncra.hims.patient.registration.dto;

import com.syncra.hims.patient.registration.AbhaNumber;
import com.syncra.hims.patient.registration.Demographics;
import com.syncra.hims.patient.registration.Gender;
import com.syncra.hims.patient.registration.MaritalStatus;
import com.syncra.hims.patient.registration.RegistrationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Registration / demographic update payload. The date of birth may be given
 * exactly, or estimated from an age ({@code ageYears}, optional {@code ageMonths}).
 * {@code registrationSource} is only used when registering.
 */
public record PatientRequest(
        @Pattern(regexp = "^(Mr|Mrs|Ms|Miss|Master|Baby|Dr|Shri|Smt|Kumari)$") String title,
        @NotBlank @Size(max = 60) String firstName,
        @Size(max = 60) String middleName,
        @Size(max = 60) String lastName,
        @NotNull Gender gender,
        @PastOrPresent LocalDate dateOfBirth,
        @Min(0) @Max(125) Integer ageYears,
        @Min(0) @Max(11) Integer ageMonths,
        @Pattern(regexp = "^(A|B|AB|O)[+-]$") String bloodGroup,
        MaritalStatus maritalStatus,
        @Pattern(regexp = "^(S/O|D/O|W/O|H/O|M/O|F/O|C/O)$") String relationType,
        @Size(max = 150) String relationName,
        @Pattern(regexp = MOBILE) String mobile,
        @Pattern(regexp = MOBILE) String alternateMobile,
        @Email @Size(max = 254) String email,
        @Valid Address address,
        @Pattern(regexp = "^\\d{2}-?\\d{4}-?\\d{4}-?\\d{4}$") String abhaNumber,
        @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+@[a-z]+$") String abhaAddress,
        @Valid EmergencyContact emergencyContact,
        RegistrationSource registrationSource,
        Boolean confidential) {

    public static final String MOBILE = "^[6-9]\\d{9}$";

    public record Address(
            @Size(max = 250) String line,
            @Size(max = 100) String cityVillage,
            @Size(max = 100) String district,
            Integer districtLgdCode,
            @Size(max = 60) String state,
            Short stateLgdCode,
            @Pattern(regexp = "^[1-9]\\d{5}$") String pincode) {
    }

    public record EmergencyContact(@Size(max = 150) String name, @Pattern(regexp = MOBILE) String mobile) {
    }

    @AssertTrue(message = "give either dateOfBirth or ageYears, not both")
    public boolean isSingleAgeSource() {
        return dateOfBirth == null || ageYears == null;
    }

    @AssertTrue(message = "ageMonths requires ageYears")
    public boolean isAgeMonthsWithYears() {
        return ageMonths == null || ageYears != null;
    }

    public RegistrationSource sourceOrDefault() {
        return registrationSource == null ? RegistrationSource.WALK_IN : registrationSource;
    }

    public boolean hasAge() {
        return dateOfBirth != null || ageYears != null;
    }

    /** Normalises input (trims, blank to null) and resolves an estimated date of birth. */
    public Demographics toDemographics(LocalDate today) {
        LocalDate dob = dateOfBirth;
        boolean estimated = false;
        if (dob == null && ageYears != null) {
            dob = today.minusYears(ageYears).minusMonths(ageMonths == null ? 0 : ageMonths);
            estimated = true;
        }
        Address a = address == null ? new Address(null, null, null, null, null, null, null) : address;
        EmergencyContact ec = emergencyContact == null ? new EmergencyContact(null, null) : emergencyContact;
        return new Demographics(
                clean(title), clean(firstName), clean(middleName), clean(lastName), gender, dob, estimated,
                clean(bloodGroup), maritalStatus, clean(relationType), clean(relationName),
                clean(mobile), clean(alternateMobile), lower(email),
                clean(a.line()), clean(a.cityVillage()), clean(a.district()), a.districtLgdCode(),
                clean(a.state()), a.stateLgdCode(), clean(a.pincode()),
                AbhaNumber.normalize(abhaNumber), lower(abhaAddress),
                clean(ec.name()), clean(ec.mobile()),
                Boolean.TRUE.equals(confidential));
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String lower(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }
}
