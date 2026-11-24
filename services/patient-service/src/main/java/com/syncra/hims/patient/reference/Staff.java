package com.syncra.hims.patient.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Member of staff ({@code core.staff}); maintained by HR/IAM, read-only here. */
@Entity
@Immutable
@Table(schema = "core", name = "staff")
public class Staff {

    public enum Role {
        DOCTOR, NURSE, RECEPTIONIST, BILLING_CLERK, PHARMACIST, LAB_TECHNICIAN,
        RADIOGRAPHER, RADIOLOGIST, MRD_OFFICER, ADMINISTRATOR
    }

    @Id
    private UUID id;

    @Column(name = "iam_subject")
    private String iamSubject;

    @Column(name = "employee_code", nullable = false)
    private String employeeCode;

    @Column(name = "title")
    private String title;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "staff_role", nullable = false)
    private Role staffRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "council_registration")
    private String councilRegistration;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Staff() {
    }

    public UUID getId() {
        return id;
    }

    public String getIamSubject() {
        return iamSubject;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getTitle() {
        return title;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDisplayName() {
        return title == null ? fullName : title + " " + fullName;
    }

    public Role getStaffRole() {
        return staffRole;
    }

    public Department getDepartment() {
        return department;
    }

    public String getCouncilRegistration() {
        return councilRegistration;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDoctor() {
        return staffRole == Role.DOCTOR;
    }
}
