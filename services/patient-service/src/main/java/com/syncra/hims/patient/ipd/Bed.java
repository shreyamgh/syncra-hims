package com.syncra.hims.patient.ipd;

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

/**
 * Bed ({@code ipd.bed}). Read-only in JPA on purpose: its status is changed by
 * database triggers (assign/release) and by compare-and-set updates in
 * {@link BedRepository}, never by writing back a possibly stale entity.
 * Read current status with {@link BedRepository#currentStatus}.
 */
@Entity
@Immutable
@Table(schema = "ipd", name = "bed")
public class Bed {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ward_id", nullable = false)
    private Ward ward;

    @Column(name = "bed_code", nullable = false)
    private String bedCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "bed_category", nullable = false)
    private BedCategory bedCategory;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Bed() {
    }

    public UUID getId() {
        return id;
    }

    public Ward getWard() {
        return ward;
    }

    public String getBedCode() {
        return bedCode;
    }

    public BedCategory getBedCategory() {
        return bedCategory;
    }

    public boolean isActive() {
        return active;
    }
}
