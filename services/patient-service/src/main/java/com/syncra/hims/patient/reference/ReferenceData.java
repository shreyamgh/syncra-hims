package com.syncra.hims.patient.reference;

import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lookups and validation of departments, doctors and tariff plans. */
@Service
@Transactional(readOnly = true)
public class ReferenceData {

    private final DepartmentRepository departments;
    private final StaffRepository staff;
    private final CurrentUser currentUser;
    private final JdbcClient jdbc;

    public ReferenceData(DepartmentRepository departments, StaffRepository staff,
                         CurrentUser currentUser, JdbcClient jdbc) {
        this.departments = departments;
        this.staff = staff;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public Department requireDepartment(String code) {
        return departments.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> ApiException.notFound("Department", code));
    }

    public Department requireOpdDepartment(String code) {
        Department department = requireDepartment(code);
        if (!department.isOpdEnabled()) {
            throw ApiException.unprocessable("NO_OPD", department.getName() + " does not run an OPD");
        }
        return department;
    }

    public List<Department> opdDepartments() {
        return departments.findByOpdEnabledTrueAndActiveTrueOrderByName();
    }

    public List<Staff> doctorsIn(String departmentCode) {
        requireDepartment(departmentCode);
        return staff.findByDepartment_CodeAndStaffRoleAndActiveTrueOrderByFullName(departmentCode, Staff.Role.DOCTOR);
    }

    /** An active doctor; when {@code department} is given the doctor must belong to it. */
    public Staff requireDoctor(UUID doctorId, Department department) {
        Staff doctor = staff.findById(doctorId)
                .filter(Staff::isActive)
                .orElseThrow(() -> ApiException.notFound("Doctor", doctorId));
        if (!doctor.isDoctor()) {
            throw ApiException.unprocessable("NOT_A_DOCTOR", doctor.getDisplayName() + " is not registered as a doctor");
        }
        if (department != null && (doctor.getDepartment() == null
                || !doctor.getDepartment().getId().equals(department.getId()))) {
            throw ApiException.unprocessable("DOCTOR_NOT_IN_DEPARTMENT",
                    doctor.getDisplayName() + " does not work in " + department.getName());
        }
        return doctor;
    }

    /** Staff record of the caller (matched on the JWT subject), if any. */
    public Optional<Staff> currentStaff() {
        return staff.findByIamSubjectAndActiveTrue(currentUser.subject());
    }

    /** Tariff plan by code, or the hospital's default plan when no code is given. */
    public UUID requireTariffPlan(String code) {
        Optional<UUID> plan = code == null || code.isBlank()
                ? jdbc.sql("SELECT id FROM billing.tariff_plan WHERE is_default AND is_active")
                        .query(UUID.class).optional()
                : jdbc.sql("SELECT id FROM billing.tariff_plan WHERE code = :code AND is_active")
                        .param("code", code).query(UUID.class).optional();
        return plan.orElseThrow(() -> ApiException.notFound("Tariff plan", code == null ? "(default)" : code));
    }
}
