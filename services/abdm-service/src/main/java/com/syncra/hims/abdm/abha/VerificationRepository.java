package com.syncra.hims.abdm.abha;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.abha_verification}: every attempt, successful or not. */
@Repository
public class VerificationRepository {

    public enum Method { MOBILE_OTP, AADHAAR_OTP, QR_SCAN }

    public record Verification(UUID id, UUID patientId, String abhaNumber, String abhaAddress, String method,
                               String outcome, String gender, Integer yearOfBirth, String performedBy,
                               Instant performedAt) {
    }

    private final JdbcClient jdbc;

    public VerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID success(Method method, AbhaProfile profile, String txnId, String actor) {
        return success(method, profile.abhaNumber(), profile.abhaAddress(), profile.gender(), profile.yearOfBirth(),
                txnId, actor);
    }

    public UUID success(Method method, String abhaNumber, String abhaAddress, String gender, Integer yearOfBirth,
                        String txnId, String actor) {
        return jdbc.sql("""
                INSERT INTO abdm.abha_verification
                    (abha_number, abha_address, method, outcome, abdm_txn_id, gender, year_of_birth, performed_by)
                VALUES (:number, :address, :method, 'SUCCESS', :txn, :gender, :year, :actor)
                RETURNING id
                """)
                .param("number", abhaNumber).param("address", abhaAddress)
                .param("method", method.name()).param("txn", txnId).param("gender", gender)
                .param("year", yearOfBirth).param("actor", actor)
                .query(UUID.class).single();
    }

    public void failure(Method method, String abhaNumber, String txnId, String reason, String actor) {
        jdbc.sql("""
                INSERT INTO abdm.abha_verification (abha_number, method, outcome, abdm_txn_id, failure_reason, performed_by)
                VALUES (:number, :method, 'FAILED', :txn, :reason, :actor)
                """)
                .param("number", abhaNumber).param("method", method.name()).param("txn", txnId)
                .param("reason", reason == null || reason.length() <= 300 ? reason : reason.substring(0, 300))
                .param("actor", actor)
                .update();
    }

    public Optional<Verification> lock(UUID id) {
        return jdbc.sql("""
                SELECT id, patient_id, abha_number, abha_address, method, outcome, gender, year_of_birth,
                       performed_by, performed_at
                FROM abdm.abha_verification WHERE id = :id FOR UPDATE
                """)
                .param("id", id)
                .query((rs, n) -> new Verification(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("abha_number"), rs.getString("abha_address"), rs.getString("method"),
                        rs.getString("outcome"), rs.getString("gender"), (Integer) rs.getObject("year_of_birth"),
                        rs.getString("performed_by"), rs.getTimestamp("performed_at").toInstant()))
                .optional();
    }

    public void setAddress(UUID verificationId, String abhaAddress) {
        jdbc.sql("UPDATE abdm.abha_verification SET abha_address = :a WHERE id = :id")
                .param("a", abhaAddress).param("id", verificationId).update();
    }

    public void attach(UUID verificationId, UUID patientId) {
        jdbc.sql("UPDATE abdm.abha_verification SET patient_id = :patient WHERE id = :id")
                .param("patient", patientId).param("id", verificationId).update();
    }
}
