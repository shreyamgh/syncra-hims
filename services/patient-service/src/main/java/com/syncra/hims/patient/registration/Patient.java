package com.syncra.hims.patient.registration;

import com.syncra.hims.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Master patient index entry ({@code patient.patient}).
 *
 * <p>{@code uhid} is assigned by the column default {@code patient.next_uhid()}
 * and {@code full_name} is a generated column; Hibernate reads both back after
 * insert. The UHID can never change (database trigger).
 */
@Entity
@Table(schema = "patient", name = "patient")
public class Patient extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "uhid", insertable = false, updatable = false)
    private String uhid;

    @Column(name = "title")
    private String title;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "full_name", insertable = false, updatable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "gender", nullable = false, length = 1)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "dob_is_estimated", nullable = false)
    private boolean dobEstimated;

    @Column(name = "blood_group")
    private String bloodGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status")
    private MaritalStatus maritalStatus;

    @Column(name = "relation_type")
    private String relationType;

    @Column(name = "relation_name")
    private String relationName;

    @Column(name = "mobile")
    private String mobile;

    @Column(name = "alternate_mobile")
    private String alternateMobile;

    @Column(name = "email")
    private String email;

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "city_village")
    private String cityVillage;

    @Column(name = "district_name")
    private String districtName;

    @Column(name = "district_lgd_code")
    private Integer districtLgdCode;

    @Column(name = "state_name")
    private String stateName;

    @Column(name = "state_lgd_code")
    private Short stateLgdCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pincode", length = 6)
    private String pincode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "abha_number", length = 14)
    private String abhaNumber;

    @Column(name = "abha_address")
    private String abhaAddress;

    @Column(name = "abha_verified_at")
    private Instant abhaVerifiedAt;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_mobile")
    private String emergencyContactMobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_source", nullable = false)
    private RegistrationSource registrationSource;

    @Column(name = "is_confidential", nullable = false)
    private boolean confidential;

    @Column(name = "is_deceased", nullable = false)
    private boolean deceased;

    @Column(name = "deceased_at")
    private Instant deceasedAt;

    @Column(name = "merged_into_id")
    private UUID mergedIntoId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Patient() {
    }

    public static Patient register(Demographics details, RegistrationSource source) {
        Patient patient = new Patient();
        patient.registrationSource = source;
        patient.apply(details);
        return patient;
    }

    /** Replaces the demographics; the ABHA verification stamp is cleared if the ABHA identity changes. */
    public void updateDemographics(Demographics details) {
        boolean abhaChanged = !Objects.equals(abhaNumber, details.abhaNumber())
                || !Objects.equals(abhaAddress, details.abhaAddress());
        apply(details);
        if (abhaChanged) {
            abhaVerifiedAt = null;
        }
    }

    public void markDeceased(Instant at) {
        deceased = true;
        deceasedAt = at;
    }

    public Demographics demographics() {
        return new Demographics(title, firstName, middleName, lastName, gender, dateOfBirth, dobEstimated,
                bloodGroup, maritalStatus, relationType, relationName, mobile, alternateMobile, email,
                addressLine, cityVillage, districtName, districtLgdCode, stateName, stateLgdCode, pincode,
                abhaNumber, abhaAddress, emergencyContactName, emergencyContactMobile, confidential);
    }

    private void apply(Demographics d) {
        title = d.title();
        firstName = d.firstName();
        middleName = d.middleName();
        lastName = d.lastName();
        gender = d.gender();
        dateOfBirth = d.dateOfBirth();
        dobEstimated = d.dobEstimated();
        bloodGroup = d.bloodGroup();
        maritalStatus = d.maritalStatus();
        relationType = d.relationType();
        relationName = d.relationName();
        mobile = d.mobile();
        alternateMobile = d.alternateMobile();
        email = d.email();
        addressLine = d.addressLine();
        cityVillage = d.cityVillage();
        districtName = d.districtName();
        districtLgdCode = d.districtLgdCode();
        stateName = d.stateName();
        stateLgdCode = d.stateLgdCode();
        pincode = d.pincode();
        abhaNumber = d.abhaNumber();
        abhaAddress = d.abhaAddress();
        emergencyContactName = d.emergencyContactName();
        emergencyContactMobile = d.emergencyContactMobile();
        confidential = d.confidential();
    }

    public UUID getId() {
        return id;
    }

    public Uhid getUhid() {
        return uhid == null ? null : new Uhid(uhid);
    }

    public String getFullName() {
        return fullName;
    }

    public Gender getGender() {
        return gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getMobile() {
        return mobile;
    }

    public String getAbhaNumber() {
        return abhaNumber;
    }

    public String getAbhaAddress() {
        return abhaAddress;
    }

    public Instant getAbhaVerifiedAt() {
        return abhaVerifiedAt;
    }

    public RegistrationSource getRegistrationSource() {
        return registrationSource;
    }

    public boolean isConfidential() {
        return confidential;
    }

    public boolean isDeceased() {
        return deceased;
    }

    public Instant getDeceasedAt() {
        return deceasedAt;
    }

    public UUID getMergedIntoId() {
        return mergedIntoId;
    }

    public boolean isActive() {
        return active;
    }
}
