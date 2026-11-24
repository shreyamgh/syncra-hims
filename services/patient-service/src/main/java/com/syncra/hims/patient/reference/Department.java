package com.syncra.hims.patient.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Hospital department ({@code core.department}); reference data, read-only here. */
@Entity
@Immutable
@Table(schema = "core", name = "department")
public class Department {

    @Id
    private UUID id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "opd_enabled", nullable = false)
    private boolean opdEnabled;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Department() {
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isOpdEnabled() {
        return opdEnabled;
    }

    public boolean isActive() {
        return active;
    }
}
