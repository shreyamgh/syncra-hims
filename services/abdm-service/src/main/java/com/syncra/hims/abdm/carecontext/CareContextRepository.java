package com.syncra.hims.abdm.carecontext;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code abdm.care_context}: one per encounter; the reference number is the encounter id. */
@Repository
public class CareContextRepository {

    public record CareContext(UUID id, UUID patientId, UUID encounterId, String referenceNumber, String display,
                              List<String> hiTypes, String linkStatus, String linkedAbhaAddress, Instant linkedAt,
                              String linkError, UUID linkRequestId, Instant encounterStartedAt) {
    }

    private static final String COLUMNS = """
            c.id, c.patient_id, c.encounter_id, c.reference_number, c.display, c.hi_types, c.link_status,
            c.linked_abha_address, c.linked_at, c.link_error, c.link_request_id, e.started_at
            """;
    private static final String FROM = " FROM abdm.care_context c JOIN clinical.encounter e ON e.id = c.encounter_id ";

    private final JdbcClient jdbc;

    public CareContextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the care context, or widens its record types. A linked care context that gains a
     * type goes back to PENDING so the new type is linked too.
     */
    public CareContext upsertPending(UUID patientId, UUID encounterId, String display, List<String> hiTypes) {
        jdbc.sql("""
                INSERT INTO abdm.care_context (patient_id, encounter_id, reference_number, display, hi_types, link_status)
                VALUES (:patient, :encounter, :reference, :display, CAST(:types AS varchar[]), 'PENDING')
                ON CONFLICT (encounter_id) DO UPDATE
                SET display = EXCLUDED.display,
                    hi_types = ARRAY(SELECT DISTINCT unnest(abdm.care_context.hi_types || EXCLUDED.hi_types) ORDER BY 1),
                    -- Unchanged when linked (or being linked) with these types already; otherwise queue a link.
                    link_status = CASE WHEN abdm.care_context.link_status IN ('LINKED', 'PENDING')
                                            AND EXCLUDED.hi_types <@ abdm.care_context.hi_types
                                       THEN abdm.care_context.link_status ELSE 'PENDING' END,
                    link_request_id = CASE WHEN abdm.care_context.link_status IN ('LINKED', 'PENDING')
                                                AND EXCLUDED.hi_types <@ abdm.care_context.hi_types
                                           THEN abdm.care_context.link_request_id END,
                    link_error = CASE WHEN abdm.care_context.link_status IN ('LINKED', 'PENDING')
                                           AND EXCLUDED.hi_types <@ abdm.care_context.hi_types
                                      THEN abdm.care_context.link_error END
                """)
                .param("patient", patientId).param("encounter", encounterId).param("reference", encounterId.toString())
                .param("display", display).param("types", "{" + String.join(",", hiTypes) + "}")
                .update();
        return byEncounter(encounterId).orElseThrow();
    }

    /**
     * Makes sure a discovered encounter has a care context to offer. An existing one keeps its link
     * status; a new one starts as NOT_LINKED (the patient links it from the PHR app).
     */
    public CareContext upsertDiscovered(UUID patientId, UUID encounterId, String display, List<String> hiTypes) {
        jdbc.sql("""
                INSERT INTO abdm.care_context (patient_id, encounter_id, reference_number, display, hi_types, link_status)
                VALUES (:patient, :encounter, :reference, :display, CAST(:types AS varchar[]), 'NOT_LINKED')
                ON CONFLICT (encounter_id) DO UPDATE
                SET display = EXCLUDED.display,
                    hi_types = ARRAY(SELECT DISTINCT unnest(abdm.care_context.hi_types || EXCLUDED.hi_types) ORDER BY 1)
                """)
                .param("patient", patientId).param("encounter", encounterId).param("reference", encounterId.toString())
                .param("display", display).param("types", "{" + String.join(",", hiTypes) + "}")
                .update();
        return byEncounter(encounterId).orElseThrow();
    }

    public List<CareContext> byIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.id IN (:ids) ORDER BY e.started_at DESC")
                .param("ids", ids).query(CareContextRepository::map).list();
    }

    /** Links care contexts the patient confirmed by OTP, unless one is linked to another ABHA meanwhile. */
    public int markLinkedByPatient(List<UUID> ids, String abhaAddress, Instant at) {
        return jdbc.sql("""
                UPDATE abdm.care_context
                SET link_status = 'LINKED', linked_abha_address = :address, linked_at = :at, link_error = NULL,
                    link_request_id = NULL
                WHERE id IN (:ids)
                  AND (link_status <> 'LINKED' OR lower(linked_abha_address) = lower(:address))
                """).param("address", abhaAddress).param("at", java.sql.Timestamp.from(at)).param("ids", ids).update();
    }

    public Optional<CareContext> byEncounter(UUID encounterId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.encounter_id = :id")
                .param("id", encounterId).query(CareContextRepository::map).optional();
    }

    public Optional<CareContext> byReference(String referenceNumber) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.reference_number = :ref")
                .param("ref", referenceNumber).query(CareContextRepository::map).optional();
    }

    public List<CareContext> forPatient(UUID patientId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.patient_id = :id ORDER BY e.started_at DESC")
                .param("id", patientId).query(CareContextRepository::map).list();
    }

    public List<CareContext> forConsent(UUID consentRowId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + """
                JOIN abdm.consent_care_context cc ON cc.care_context_id = c.id
                WHERE cc.consent_id = :consent ORDER BY e.started_at
                """).param("consent", consentRowId).query(CareContextRepository::map).list();
    }

    /** Claims the patient's care contexts waiting for a link call, tagging them with its REQUEST-ID. */
    public List<CareContext> claimAwaitingLink(UUID patientId, UUID requestId) {
        jdbc.sql("""
                UPDATE abdm.care_context SET link_request_id = :request
                WHERE id IN (SELECT id FROM abdm.care_context
                             WHERE patient_id = :patient AND link_status = 'PENDING' AND link_request_id IS NULL
                             FOR UPDATE SKIP LOCKED)
                """).param("request", requestId).param("patient", patientId).update();
        return byLinkRequest(requestId);
    }

    public List<CareContext> byLinkRequest(UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.link_request_id = :id ORDER BY e.started_at")
                .param("id", requestId).query(CareContextRepository::map).list();
    }

    public boolean hasAwaitingLink(UUID patientId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM abdm.care_context
                               WHERE patient_id = :id AND link_status = 'PENDING' AND link_request_id IS NULL)
                """).param("id", patientId).query(Boolean.class).single();
    }

    /** Puts a failed link call's care contexts back in the queue for the next link token. */
    public void requeue(UUID requestId) {
        jdbc.sql("UPDATE abdm.care_context SET link_request_id = NULL WHERE link_request_id = :id AND link_status = 'PENDING'")
                .param("id", requestId).update();
    }

    public int markLinked(UUID requestId, String abhaAddress, Instant at) {
        return jdbc.sql("""
                UPDATE abdm.care_context
                SET link_status = 'LINKED', linked_abha_address = :address, linked_at = :at, link_error = NULL
                WHERE link_request_id = :id AND link_status = 'PENDING'
                """).param("address", abhaAddress).param("at", java.sql.Timestamp.from(at)).param("id", requestId)
                .update();
    }

    public int markFailed(UUID requestId, String error) {
        return jdbc.sql("""
                UPDATE abdm.care_context SET link_status = 'FAILED', link_error = :error
                WHERE link_request_id = :id AND link_status = 'PENDING'
                """).param("error", truncate(error)).param("id", requestId).update();
    }

    /** Fails the patient's care contexts that were still waiting for a link token. */
    public int failAwaiting(UUID patientId, String error) {
        return jdbc.sql("""
                UPDATE abdm.care_context SET link_status = 'FAILED', link_error = :error
                WHERE patient_id = :patient AND link_status = 'PENDING' AND link_request_id IS NULL
                """).param("error", truncate(error)).param("patient", patientId).update();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static CareContext map(ResultSet rs, int row) throws SQLException {
        Array types = rs.getArray("hi_types");
        java.sql.Timestamp linkedAt = rs.getTimestamp("linked_at");
        return new CareContext(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("encounter_id", UUID.class), rs.getString("reference_number"), rs.getString("display"),
                Arrays.asList((String[]) types.getArray()), rs.getString("link_status"),
                rs.getString("linked_abha_address"), linkedAt == null ? null : linkedAt.toInstant(),
                rs.getString("link_error"), rs.getObject("link_request_id", UUID.class),
                rs.getTimestamp("started_at").toInstant());
    }
}
