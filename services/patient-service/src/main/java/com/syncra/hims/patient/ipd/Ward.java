package com.syncra.hims.patient.ipd;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Ward ({@code ipd.ward}); configured reference data. */
@Entity
@Immutable
@Table(schema = "ipd", name = "ward")
public class Ward {

    @Id
    private UUID id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "ward_type", nullable = false)
    private String wardType;

    @Column(name = "gender_restriction", nullable = false)
    private String genderRestriction;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_bed_category", nullable = false)
    private BedCategory defaultBedCategory;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Ward() {
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

    public String getWardType() {
        return wardType;
    }

    public String getGenderRestriction() {
        return genderRestriction;
    }

    public boolean isActive() {
        return active;
    }
}
