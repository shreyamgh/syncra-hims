package com.syncra.hims.iam.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Refresh tokens (stored as SHA-256) and the sign-in event log. */
@Repository
public class SessionRepository {

    public record RefreshToken(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {
    }

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, String tokenHash, UUID familyId, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO iam.refresh_token (user_id, token_hash, family_id, expires_at)
                VALUES (:u, :h, :f, :e) RETURNING id
                """).param("u", userId).param("h", tokenHash).param("f", familyId).param("e", Timestamp.from(expiresAt))
                .query(UUID.class).single();
    }

    public Optional<RefreshToken> lockByHash(String tokenHash) {
        return jdbc.sql("""
                SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by FROM iam.refresh_token
                WHERE token_hash = :h FOR UPDATE
                """).param("h", tokenHash).query((rs, n) -> new RefreshToken(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getObject("replaced_by", UUID.class))).optional();
    }

    public void rotate(UUID id, UUID replacedBy, Instant at) {
        jdbc.sql("UPDATE iam.refresh_token SET revoked_at = :at, replaced_by = :r WHERE id = :id")
                .param("at", Timestamp.from(at)).param("r", replacedBy).param("id", id).update();
    }

    public void revokeFamily(UUID familyId, Instant at) {
        jdbc.sql("UPDATE iam.refresh_token SET revoked_at = :at WHERE family_id = :f AND revoked_at IS NULL")
                .param("at", Timestamp.from(at)).param("f", familyId).update();
    }

    public void revokeAll(UUID userId, Instant at) {
        jdbc.sql("UPDATE iam.refresh_token SET revoked_at = :at WHERE user_id = :u AND revoked_at IS NULL")
                .param("at", Timestamp.from(at)).param("u", userId).update();
    }

    public void event(String username, String event, String actor, String clientIp) {
        jdbc.sql("INSERT INTO iam.auth_event (username, event, actor, client_ip) VALUES (:u, :e, :a, :ip)")
                .param("u", username).param("e", event).param("a", actor).param("ip", clientIp).update();
    }
}
