package com.syncra.hims.patient.ipd;

import com.syncra.hims.common.persistence.AuditedEntity;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.encounter.Encounter;
import com.syncra.hims.patient.reference.Department;
import com.syncra.hims.patient.reference.Staff;
import com.syncra.hims.patient.registration.Patient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Inpatient stay ({@code ipd.admission}). The admission number
 * ({@code ADM/YY-YY/NNNNNN}) is issued by the database; a patient can have only
 * one active admission (partial unique index).
 */
@Entity
@Table(schema = "ipd", name = "admission")
public class Admission extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "admission_number", insertable = false, updatable = false)
    private String admissionNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, updatable = false)
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admitting_doctor_id", nullable = false)
    private Staff admittingDoctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "admission_type", nullable = false)
    private AdmissionType admissionType;

    @Column(name = "admitted_at", nullable = false, updatable = false)
    private Instant admittedAt;

    @Column(name = "provisional_diagnosis")
    private String provisionalDiagnosis;

    @Column(name = "expected_discharge_date")
    private LocalDate expectedDischargeDate;

    @Column(name = "tariff_plan_id", nullable = false)
    private UUID tariffPlanId;

    @Column(name = "insurer_or_scheme")
    private String insurerOrScheme;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "preauth_number")
    private String preauthNumber;

    @Column(name = "preauth_amount")
    private BigDecimal preauthAmount;

    @Column(name = "attendant_name")
    private String attendantName;

    @Column(name = "attendant_relation")
    private String attendantRelation;

    @Column(name = "attendant_mobile")
    private String attendantMobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AdmissionStatus status;

    @Column(name = "discharge_initiated_at")
    private Instant dischargeInitiatedAt;

    @Column(name = "discharged_at")
    private Instant dischargedAt;

    protected Admission() {
    }

    /** Details captured at the admission desk. */
    public record Details(AdmissionType type, String provisionalDiagnosis, LocalDate expectedDischargeDate,
                          UUID tariffPlanId, String insurerOrScheme, String policyNumber, String preauthNumber,
                          BigDecimal preauthAmount, String attendantName, String attendantRelation,
                          String attendantMobile) {
    }

    public static Admission admit(Encounter encounter, Department department, Staff doctor, Details d, Instant at) {
        Admission admission = new Admission();
        admission.encounter = encounter;
        admission.patient = encounter.getPatient();
        admission.department = department;
        admission.admittingDoctor = doctor;
        admission.admissionType = d.type();
        admission.admittedAt = at;
        admission.provisionalDiagnosis = d.provisionalDiagnosis();
        admission.expectedDischargeDate = d.expectedDischargeDate();
        admission.tariffPlanId = d.tariffPlanId();
        admission.insurerOrScheme = d.insurerOrScheme();
        admission.policyNumber = d.policyNumber();
        admission.preauthNumber = d.preauthNumber();
        admission.preauthAmount = d.preauthAmount();
        admission.attendantName = d.attendantName();
        admission.attendantRelation = d.attendantRelation();
        admission.attendantMobile = d.attendantMobile();
        admission.status = AdmissionStatus.ADMITTED;
        return admission;
    }

    public void requireActive() {
        if (!status.isActive()) {
            throw ApiException.conflict("ADMISSION_CLOSED", "Admission " + admissionNumber + " is " + status);
        }
    }

    /** Signals billing to prepare the final bill; the bed is kept until discharge. */
    public void initiateDischarge(Instant at) {
        if (status != AdmissionStatus.ADMITTED) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Admission " + admissionNumber + " is " + status + "; discharge cannot be initiated");
        }
        status = AdmissionStatus.DISCHARGE_INITIATED;
        dischargeInitiatedAt = at;
    }

    /** Closes the stay. The caller must release the bed first (enforced by a database trigger). */
    public void close(DischargeOutcome outcome, Instant at) {
        requireActive();
        if (at.isBefore(admittedAt)) {
            throw ApiException.unprocessable("INVALID_TIME", "Discharge time is before the admission time");
        }
        status = outcome.toStatus();
        dischargedAt = at;
        encounter.finish(at);
        if (outcome == DischargeOutcome.EXPIRED) {
            patient.markDeceased(at);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAdmissionNumber() {
        return admissionNumber;
    }

    public Encounter getEncounter() {
        return encounter;
    }

    public Patient getPatient() {
        return patient;
    }

    public Department getDepartment() {
        return department;
    }

    public Staff getAdmittingDoctor() {
        return admittingDoctor;
    }

    public AdmissionType getAdmissionType() {
        return admissionType;
    }

    public Instant getAdmittedAt() {
        return admittedAt;
    }

    public String getProvisionalDiagnosis() {
        return provisionalDiagnosis;
    }

    public LocalDate getExpectedDischargeDate() {
        return expectedDischargeDate;
    }

    public AdmissionStatus getStatus() {
        return status;
    }

    public Instant getDischargeInitiatedAt() {
        return dischargeInitiatedAt;
    }

    public Instant getDischargedAt() {
        return dischargedAt;
    }
}
