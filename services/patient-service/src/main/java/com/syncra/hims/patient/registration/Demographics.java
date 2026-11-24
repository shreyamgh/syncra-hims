package com.syncra.hims.patient.registration;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, normalised demographic details of a patient (the domain input for
 * registration and updates, independent of the HTTP representation).
 */
public record Demographics(
        String title,
        String firstName,
        String middleName,
        String lastName,
        Gender gender,
        LocalDate dateOfBirth,
        boolean dobEstimated,
        String bloodGroup,
        MaritalStatus maritalStatus,
        String relationType,
        String relationName,
        String mobile,
        String alternateMobile,
        String email,
        String addressLine,
        String cityVillage,
        String districtName,
        Integer districtLgdCode,
        String stateName,
        Short stateLgdCode,
        String pincode,
        String abhaNumber,
        String abhaAddress,
        String emergencyContactName,
        String emergencyContactMobile,
        boolean confidential) {

    public String fullName() {
        return firstName
                + (middleName == null ? "" : " " + middleName)
                + (lastName == null ? "" : " " + lastName);
    }

    /** Names of the fields that differ — for the audit trail (never the values). */
    public Set<String> changedFields(Demographics other) {
        Set<String> changed = new LinkedHashSet<>();
        for (RecordComponent component : Demographics.class.getRecordComponents()) {
            try {
                Object mine = component.getAccessor().invoke(this);
                Object theirs = component.getAccessor().invoke(other);
                if (!Objects.equals(mine, theirs)) {
                    changed.add(component.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return changed;
    }
}
