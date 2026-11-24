package com.syncra.hims.patient.opd;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * OPD registration holding a daily token ({@code opd.visit}). The token number is
 * assigned by a database trigger from a per-department daily counter, so tokens
 * are sequential and unique even with several registration desks.
 */
@Entity
@Table(schema = "opd", name = "visit")
public class OpdVisit extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, updatable = false)
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Staff doctor;

    @Column(name = "visit_date", nullable = false, updatable = false)
    private LocalDate visitDate;

    @Generated(event = EventType.INSERT)
    @Column(name = "token_number", insertable = false, updatable = false)
    private Integer tokenNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_type", nullable = false)
    private VisitType visitType;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private VisitPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_status", nullable = false)
    private QueueStatus queueStatus;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "consultation_started_at")
    private Instant consultationStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "follow_up_of_visit_id")
    private UUID followUpOfVisitId;

    protected OpdVisit() {
    }

    public static OpdVisit register(Encounter encounter, Department department, Staff doctor, LocalDate visitDate,
                                    VisitType type, VisitPriority priority, Instant at, UUID followUpOf) {
        OpdVisit visit = new OpdVisit();
        visit.encounter = encounter;
        visit.patient = encounter.getPatient();
        visit.department = department;
        visit.doctor = doctor;
        visit.visitDate = visitDate;
        visit.visitType = type;
        visit.priority = priority;
        visit.queueStatus = QueueStatus.WAITING;
        visit.registeredAt = at;
        visit.followUpOfVisitId = followUpOf;
        return visit;
    }

    /**
     * Applies a queue transition, stamping its time and keeping the encounter in
     * step. Illegal transitions are rejected with 409 INVALID_TRANSITION.
     */
    public void moveTo(QueueStatus target, Instant at, Staff actingDoctor) {
        if (!queueStatus.canMoveTo(target)) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Token " + tokenNumber + " is " + queueStatus + " and cannot move to " + target);
        }
        switch (target) {
            case CALLED -> {
                calledAt = at;
                if (doctor == null && actingDoctor != null) {
                    doctor = actingDoctor;
                }
            }
            case IN_CONSULTATION -> {
                if (consultationStartedAt == null) {
                    consultationStartedAt = at;
                }
                if (actingDoctor != null) {
                    doctor = actingDoctor;
                }
                encounter.begin(doctor);
            }
            case COMPLETED -> {
                completedAt = at;
                encounter.finish(at);
            }
            case NO_SHOW, CANCELLED -> encounter.cancel(at);
            case WAITING, ON_HOLD -> {
                // Back into (or parked in) the queue; timestamps of earlier steps are kept.
            }
        }
        queueStatus = target;
    }

    public UUID getId() {
        return id;
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

    public Staff getDoctor() {
        return doctor;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public Integer getTokenNumber() {
        return tokenNumber;
    }

    public VisitType getVisitType() {
        return visitType;
    }

    public VisitPriority getPriority() {
        return priority;
    }

    public QueueStatus getQueueStatus() {
        return queueStatus;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public Instant getConsultationStartedAt() {
        return consultationStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
