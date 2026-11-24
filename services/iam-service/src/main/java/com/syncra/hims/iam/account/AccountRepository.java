package com.syncra.hims.iam.account;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /** An account as stored (with its hash: never sent to a client). */
    public record Account(UUID id, String username, String passwordHash, String displayName, UUID staffId,
                          List<String> roles, String status, int failedAttempts, Instant lockedUntil,
                          boolean mustChangePassword, Instant passwordChangedAt, Instant lastLoginAt, Instant createdAt) {

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    /** A member of staff, for linking an account to them. */
    public record StaffMember(UUID id, String fullName, String staffRole, String department, String iamSubject,
                              boolean hasAccount) {
    }

    private static final String SELECT = """
            SELECT id, username, password_hash, display_name, staff_id, roles, status, failed_attempts, locked_until,
                   must_change_password, password_changed_at, last_login_at, created_at
            FROM iam.user_account
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> byUsername(String username) {
        return jdbc.sql(SELECT + " WHERE username = :u").param("u", username).query(AccountRepository::map).optional();
    }

    public Optional<Account> lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query(AccountRepository::map).optional();
    }

    public Optional<Account> lockByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE username = :u FOR UPDATE").param("u", username).query(AccountRepository::map).optional();
    }

    public List<Account> all() {
        return jdbc.sql(SELECT + " ORDER BY status, display_name").query(AccountRepository::map).list();
    }

    public UUID insert(String username, String hash, String displayName, UUID staffId, List<String> roles, String createdBy) {
        return jdbc.sql("""
                INSERT INTO iam.user_account (username, password_hash, display_name, staff_id, roles, created_by)
                VALUES (:u, :h, :n, :s, CAST(:r AS text[]), :by) RETURNING id
                """).param("u", username).param("h", hash).param("n", displayName).param("s", staffId)
                .param("r", "{" + String.join(",", roles) + "}").param("by", createdBy).query(UUID.class).single();
    }

    public void recordFailure(UUID id, int attempts, Instant lockedUntil) {
        jdbc.sql("UPDATE iam.user_account SET failed_attempts = :a, locked_until = :l WHERE id = :id")
                .param("a", attempts).param("l", lockedUntil == null ? null : Timestamp.from(lockedUntil)).param("id", id)
                .update();
    }

    public void recordLogin(UUID id, Instant at) {
        jdbc.sql("UPDATE iam.user_account SET failed_attempts = 0, locked_until = NULL, last_login_at = :at WHERE id = :id")
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    public void setPassword(UUID id, String hash, boolean mustChange, Instant at) {
        jdbc.sql("""
                UPDATE iam.user_account SET password_hash = :h, must_change_password = :m, password_changed_at = :at,
                       failed_attempts = 0, locked_until = NULL
                WHERE id = :id
                """).param("h", hash).param("m", mustChange).param("at", Timestamp.from(at)).param("id", id).update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE iam.user_account SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    public void unlock(UUID id) {
        jdbc.sql("UPDATE iam.user_account SET failed_attempts = 0, locked_until = NULL WHERE id = :id").param("id", id).update();
    }

    public void setRoles(UUID id, List<String> roles) {
        jdbc.sql("UPDATE iam.user_account SET roles = CAST(:r AS text[]) WHERE id = :id")
                .param("r", "{" + String.join(",", roles) + "}").param("id", id).update();
    }

    public List<StaffMember> staff() {
        return jdbc.sql("""
                SELECT s.id, s.full_name, s.staff_role, d.name AS department, s.iam_subject,
                       EXISTS (SELECT 1 FROM iam.user_account a WHERE a.staff_id = s.id) AS has_account
                FROM core.staff s LEFT JOIN core.department d ON d.id = s.department_id
                WHERE s.is_active
                ORDER BY s.full_name
                """).query((rs, n) -> new StaffMember(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("staff_role"), rs.getString("department"), rs.getString("iam_subject"),
                        rs.getBoolean("has_account"))).list();
    }

    public Optional<StaffMember> staffMember(UUID id) {
        return staff().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Links a staff record to the account's username (the token's subject). */
    public void linkStaff(UUID staffId, String username) {
        jdbc.sql("UPDATE core.staff SET iam_subject = :u WHERE id = :id AND (iam_subject IS NULL OR iam_subject = :u)")
                .param("u", username).param("id", staffId).update();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        Array roles = rs.getArray("roles");
        return new Account(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getObject("staff_id", UUID.class),
                Arrays.asList((String[]) roles.getArray()), rs.getString("status"), rs.getInt("failed_attempts"),
                instant(rs, "locked_until"), rs.getBoolean("must_change_password"), instant(rs, "password_changed_at"),
                instant(rs, "last_login_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
