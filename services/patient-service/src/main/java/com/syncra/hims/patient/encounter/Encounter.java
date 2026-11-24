package com.syncra.hims.patient.encounter;

import com.syncra.hims.common.persistence.AuditedEntity;
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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One episode of care ({@code clinical.encounter}): an OPD visit, an IPD stay or
 * an emergency attendance. The EMR, billing and ABDM care contexts hang off it.
 */
@Entity
@Table(schema = "clinical", name = "encounter")
public class Encounter extends AuditedEntity {

    public enum Type { OPD, IPD, EMERGENCY, DAYCARE }

    public enum Status { PLANNED, ARRIVED, IN_PROGRESS, FINISHED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_type", nullable = false)
    private Type encounterType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attending_doctor_id")
    private Staff attendingDoctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "is_mlc", nullable = false)
    private boolean mlc;

    @Column(name = "referred_by")
    private String referredBy;

    protected Encounter() {
    }

    public static Encounter open(Patient patient, Type type, Department department, Staff doctor,
                                 Status status, Instant at, String referredBy, boolean mlc) {
        Encounter encounter = new Encounter();
        encounter.patient = patient;
        encounter.encounterType = type;
        encounter.department = department;
        encounter.attendingDoctor = doctor;
        encounter.status = status;
        encounter.startedAt = at;
        encounter.referredBy = referredBy;
        encounter.mlc = mlc;
        return encounter;
    }

    public void begin(Staff doctor) {
        status = Status.IN_PROGRESS;
        if (doctor != null) {
            attendingDoctor = doctor;
        }
    }

    public void finish(Instant at) {
        status = Status.FINISHED;
        endedAt = at.isBefore(startedAt) ? startedAt : at;
    }

    public void cancel(Instant at) {
        status = Status.CANCELLED;
        endedAt = at.isBefore(startedAt) ? startedAt : at;
    }

    public UUID getId() {
        return id;
    }

    public Patient getPatient() {
        return patient;
    }

    public Type getEncounterType() {
        return encounterType;
    }

    public Department getDepartment() {
        return department;
    }

    public Staff getAttendingDoctor() {
        return attendingDoctor;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
