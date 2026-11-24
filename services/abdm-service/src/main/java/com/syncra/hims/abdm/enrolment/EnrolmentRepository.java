package com.syncra.hims.abdm.enrolment;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.abha_enrolment}: one row per enrolment, updated as it progresses; no Aadhaar. */
@Repository
public class EnrolmentRepository {

    private final JdbcClient jdbc;

    public EnrolmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void failed(UUID id, String consentVersion, String reason, String actor) {
        jdbc.sql("""
                INSERT INTO abdm.abha_enrolment (id, outcome, consent_version, failure_reason, performed_by)
                VALUES (:id, 'FAILED', :consent, :reason, :actor)
                ON CONFLICT (id) DO UPDATE SET failure_reason = EXCLUDED.failure_reason
                WHERE abdm.abha_enrolment.outcome = 'FAILED'
                """).param("id", id).param("consent", consentVersion).param("reason", truncate(reason))
                .param("actor", actor).update();
    }

    public void created(UUID id, boolean isNew, String abhaNumber, String abhaAddress, boolean mobileVerified,
                        String consentVersion, UUID verificationId, String actor) {
        jdbc.sql("""
                INSERT INTO abdm.abha_enrolment (id, abha_number, abha_address, outcome, mobile_verified, consent_version,
                                                 verification_id, performed_by)
                VALUES (:id, :number, :address, :outcome, :mobile, :consent, :verification, :actor)
                ON CONFLICT (id) DO UPDATE
                SET abha_number = EXCLUDED.abha_number, abha_address = EXCLUDED.abha_address,
                    outcome = EXCLUDED.outcome, mobile_verified = EXCLUDED.mobile_verified,
                    verification_id = EXCLUDED.verification_id, failure_reason = NULL
                """).param("id", id).param("number", abhaNumber).param("address", abhaAddress)
                .param("outcome", isNew ? "CREATED" : "EXISTING").param("mobile", mobileVerified)
                .param("consent", consentVersion).param("verification", verificationId).param("actor", actor)
                .update();
    }

    public void mobileVerified(UUID id) {
        jdbc.sql("UPDATE abdm.abha_enrolment SET mobile_verified = true WHERE id = :id").param("id", id).update();
    }

    public void address(UUID id, String abhaAddress) {
        jdbc.sql("UPDATE abdm.abha_enrolment SET abha_address = :a WHERE id = :id")
                .param("a", abhaAddress).param("id", id).update();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }
}
