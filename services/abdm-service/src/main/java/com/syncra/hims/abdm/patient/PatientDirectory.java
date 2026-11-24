package com.syncra.hims.abdm.patient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read access to the master patient index, and the one write this service owns there:
 * the verified ABHA identity ({@code abha_number}, {@code abha_address}, {@code abha_verified_at}).
 */
@Repository
public class PatientDirectory {

    public record Patient(UUID id, String uhid, String fullName, String firstName, String lastName, String gender,
                          LocalDate dateOfBirth, boolean dobEstimated, String mobile, String abhaNumber,
                          String abhaAddress, Instant abhaVerifiedAt, boolean active) {

        public boolean hasVerifiedAbha() {
            return abhaVerifiedAt != null && (abhaNumber != null || abhaAddress != null);
        }
    }

    private static final String COLUMNS = """
            id, uhid, full_name, first_name, last_name, gender, date_of_birth, dob_is_estimated, mobile,
            abha_number, abha_address, abha_verified_at, is_active AND merged_into_id IS NULL AS active
            """;

    private final JdbcClient jdbc;

    public PatientDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Patient> find(UUID patientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM patient.patient WHERE id = :id")
                .param("id", patientId).query(PatientDirectory::map).optional();
    }

    public Optional<Patient> lock(UUID patientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM patient.patient WHERE id = :id FOR UPDATE")
                .param("id", patientId).query(PatientDirectory::map).optional();
    }

    public Optional<Patient> byUhid(String uhid) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM patient.patient WHERE uhid = :uhid AND merged_into_id IS NULL")
                .param("uhid", uhid).query(PatientDirectory::map).optional();
    }

    /** Active patient holding this ABHA number or address. */
    public Optional<Patient> byAbha(String abhaNumber, String abhaAddress) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM patient.patient
                WHERE merged_into_id IS NULL
                  AND ((CAST(:number AS text) IS NOT NULL AND abha_number = :number)
                       OR (CAST(:address AS text) IS NOT NULL AND lower(abha_address) = lower(:address)))
                ORDER BY abha_verified_at DESC NULLS LAST
                LIMIT 1
                """)
                .param("number", abhaNumber).param("address", abhaAddress)
                .query(PatientDirectory::map).optional();
    }

    /** Stamps the verified ABHA on the record; bumps the JPA version the Patient Service checks. */
    public void setVerifiedAbha(UUID patientId, String abhaNumber, String abhaAddress, Instant verifiedAt,
                                String actor) {
        jdbc.sql("""
                UPDATE patient.patient
                SET abha_number = :number, abha_address = :address, abha_verified_at = :at,
                    version = version + 1, updated_by = :actor
                WHERE id = :id
                """)
                .param("number", abhaNumber).param("address", abhaAddress)
                .param("at", java.sql.Timestamp.from(verifiedAt)).param("actor", actor).param("id", patientId)
                .update();
    }

    private static Patient map(ResultSet rs, int row) throws SQLException {
        java.sql.Date dob = rs.getDate("date_of_birth");
        java.sql.Timestamp verified = rs.getTimestamp("abha_verified_at");
        return new Patient(rs.getObject("id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("gender"),
                dob == null ? null : dob.toLocalDate(), rs.getBoolean("dob_is_estimated"), rs.getString("mobile"),
                rs.getString("abha_number"), rs.getString("abha_address"),
                verified == null ? null : verified.toInstant(), rs.getBoolean("active"));
    }
}
