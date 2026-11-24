package com.syncra.hims.patient.ipd;

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
 * One period of a bed being held by an admission ({@code ipd.bed_assignment}).
 * Inserting a row marks the bed OCCUPIED and releasing it marks it CLEANING
 * (database trigger). Exclusion constraints make double-booking impossible.
 */
@Entity
@Table(schema = "ipd", name = "bed_assignment")
public class BedAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admission_id", nullable = false, updatable = false)
    private Admission admission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id", nullable = false, updatable = false)
    private Bed bed;

    @Enumerated(EnumType.STRING)
    @Column(name = "bed_category", nullable = false, updatable = false)
    private BedCategory bedCategory;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private AssignmentReason reason;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private String assignedBy;

    @Column(name = "released_by")
    private String releasedBy;

    protected BedAssignment() {
    }

    public static BedAssignment assign(Admission admission, Bed bed, AssignmentReason reason, Instant at, String by) {
        BedAssignment assignment = new BedAssignment();
        assignment.admission = admission;
        assignment.bed = bed;
        assignment.bedCategory = bed.getBedCategory();
        assignment.reason = reason;
        assignment.assignedAt = at;
        assignment.assignedBy = by;
        return assignment;
    }

    public void release(Instant at, String by) {
        releasedAt = at;
        releasedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public Admission getAdmission() {
        return admission;
    }

    public Bed getBed() {
        return bed;
    }

    public BedCategory getBedCategory() {
        return bedCategory;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public AssignmentReason getReason() {
        return reason;
    }
}
