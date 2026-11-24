package com.syncra.hims.patient.registration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-side queries over the master patient index, using the pg_trgm GIN index
 * on {@code full_name} for fuzzy name search. Merged and inactive records are
 * excluded.
 */
@Repository
public class PatientSearch {

    /** Lightweight row for lists; mapped to a masked {@code PatientSummary} by the service. */
    public record Row(UUID id, String uhid, String fullName, Gender gender, LocalDate dateOfBirth,
                      String mobile, boolean abhaLinked, boolean deceased, Instant registeredAt) {
    }

    private static final String SELECT = """
            SELECT p.id, p.uhid, p.full_name, p.gender, p.date_of_birth, p.mobile,
                   (p.abha_number IS NOT NULL OR p.abha_address IS NOT NULL) AS abha_linked,
                   p.is_deceased, p.created_at
            FROM patient.patient AS p
            WHERE p.merged_into_id IS NULL AND p.is_active
            """;

    private final JdbcClient jdbc;

    public PatientSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> byUhid(String uhid) {
        return jdbc.sql(SELECT + " AND p.uhid = :uhid").param("uhid", uhid).query(PatientSearch::map).list();
    }

    public List<Row> byMobile(String mobile, int limit, int offset) {
        return jdbc.sql(SELECT + """
                 AND (p.mobile = :mobile OR p.alternate_mobile = :mobile)
                ORDER BY p.created_at DESC LIMIT :limit OFFSET :offset
                """)
                .param("mobile", mobile).param("limit", limit).param("offset", offset)
                .query(PatientSearch::map).list();
    }

    public List<Row> byAbhaNumber(String digits) {
        return jdbc.sql(SELECT + " AND p.abha_number = :abha").param("abha", digits).query(PatientSearch::map).list();
    }

    public List<Row> byAbhaAddress(String address) {
        return jdbc.sql(SELECT + " AND lower(p.abha_address) = lower(:abha)")
                .param("abha", address).query(PatientSearch::map).list();
    }

    /** Trigram similarity, plus substring match for short fragments ("ram" in "Ramesh"). */
    public List<Row> byName(String query, int limit, int offset) {
        return jdbc.sql(SELECT + """
                 AND (p.full_name % :q OR p.full_name ILIKE :contains)
                ORDER BY similarity(p.full_name, :q) DESC, p.created_at DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("q", query)
                .param("contains", "%" + escapeLike(query) + "%")
                .param("limit", limit).param("offset", offset)
                .query(PatientSearch::map).list();
    }

    /**
     * Probable existing records for the same person: same gender and either the
     * same mobile with a similar name, or the same date of birth with a very
     * similar name.
     */
    public List<Row> potentialDuplicates(Demographics d) {
        if (d.mobile() == null && d.dateOfBirth() == null) {
            return List.of();
        }
        return jdbc.sql(SELECT + """
                 AND p.gender = :gender
                 AND ((p.mobile = :mobile AND similarity(p.full_name, :name) >= 0.4)
                      OR (p.date_of_birth = :dob AND similarity(p.full_name, :name) >= 0.6))
                ORDER BY similarity(p.full_name, :name) DESC
                LIMIT 5
                """)
                .param("gender", d.gender().name())
                .param("mobile", d.mobile() == null ? "" : d.mobile())
                .param("dob", d.dateOfBirth() == null ? LocalDate.of(1, 1, 1) : d.dateOfBirth())
                .param("name", d.fullName())
                .query(PatientSearch::map).list();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("uhid"),
                rs.getString("full_name"),
                Gender.valueOf(rs.getString("gender").trim()),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("mobile"),
                rs.getBoolean("abha_linked"),
                rs.getBoolean("is_deceased"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
