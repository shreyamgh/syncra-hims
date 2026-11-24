package com.syncra.hims.patient.opd.dto;

import com.syncra.hims.patient.reference.Department;
import com.syncra.hims.patient.reference.Staff;
import java.util.UUID;

/** Department and doctor pick-lists for the registration desk. */
public final class ReferenceResponses {

    private ReferenceResponses() {
    }

    public record DepartmentItem(String code, String name) {
        public static DepartmentItem from(Department d) {
            return new DepartmentItem(d.getCode(), d.getName());
        }
    }

    public record DoctorItem(UUID id, String name, String councilRegistration) {
        public static DoctorItem from(Staff s) {
            return new DoctorItem(s.getId(), s.getDisplayName(), s.getCouncilRegistration());
        }
    }
}
