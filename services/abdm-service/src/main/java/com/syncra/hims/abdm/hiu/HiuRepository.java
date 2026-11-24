package com.syncra.hims.abdm.hiu;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The HIU tables: consent requests, the artefacts granted for them, data requests and records received. */
@Repository
public class HiuRepository {

    public record ConsentRequest(UUID id, UUID gatewayRequestId, UUID consentRequestId, UUID patientId,
                                 String abhaAddress, String purpose, List<String> hiTypes, Instant from, Instant to,
                                 Instant eraseAt, UUID requestedBy, String requestedByName, String status, String error,
                                 Instant requestedAt) {
    }

    public record Artefact(UUID consentId, UUID requestRowId, UUID patientId, String status, String hipId,
                           String hipName, List<String> hiTypes, Instant from, Instant to, Instant eraseAt,
                           Instant fetchedAt) {

        public boolean isUsable(Instant now) {
            return "GRANTED".equals(status) && fetchedAt != null && (eraseAt == null || eraseAt.isAfter(now));
        }
    }

    public record DataRequest(UUID id, UUID gatewayRequestId, UUID consentId, UUID transactionId, Instant from,
                              Instant to, String status, Integer pageCount, int pagesReceived, String error,
                              Instant requestedAt, Instant completedAt) {

        public boolean isOpen() {
            return "REQUESTED".equals(status) || "ACKNOWLEDGED".equals(status);
        }
    }

    public record StoredRecord(UUID id, UUID dataRequestId, UUID consentId, UUID patientId, String careContextReference,
                               String recordType, String title, Instant recordDate, String hipId, String hipName,
                               Instant receivedAt, Instant eraseAt) {
    }

    public record NewRecord(UUID dataRequestId, UUID consentId, UUID patientId, String careContextReference,
                            int pageNumber, String recordType, String title, Instant recordDate, String bundleJson,
                            String sha256, Instant eraseAt) {
    }

    private static final String REQUEST_COLUMNS = """
            r.id, r.gateway_request_id, r.consent_request_id, r.patient_id, r.abha_address, r.purpose_code, r.hi_types,
            r.date_range_from, r.date_range_to, r.data_erase_at, r.requested_by, concat_ws(' ', s.title, s.full_name) AS requester,
            r.status, r.error_message, r.requested_at
            """;
    private static final String ARTEFACT_COLUMNS = """
            a.consent_id, a.consent_request_id, r.patient_id, a.status, a.hip_id, a.hip_name, a.hi_types,
            a.date_range_from, a.date_range_to, a.data_erase_at, a.fetched_at
            """;
    private static final String DATA_COLUMNS = """
            id, gateway_request_id, consent_id, transaction_id, date_range_from, date_range_to, status, page_count,
            pages_received, error_message, requested_at, completed_at
            """;
    private static final String RECORD_COLUMNS = """
            h.id, h.data_request_id, h.consent_id, h.patient_id, h.care_context_reference, h.record_type, h.title,
            h.record_date, a.hip_id, a.hip_name, h.received_at, h.data_erase_at
            """;

    private final JdbcClient jdbc;

    public HiuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ consent requests

    public UUID insertConsentRequest(UUID gatewayRequestId, UUID patientId, String abhaAddress, String purpose,
                                     List<String> hiTypes, Instant from, Instant to, Instant eraseAt, UUID requestedBy) {
        return jdbc.sql("""
                INSERT INTO abdm.hiu_consent_request (gateway_request_id, patient_id, abha_address, purpose_code, hi_types,
                                                      date_range_from, date_range_to, data_erase_at, requested_by)
                VALUES (:g, :p, :a, :purpose, CAST(:types AS varchar[]), :from, :to, :erase, :by) RETURNING id
                """).param("g", gatewayRequestId).param("p", patientId).param("a", abhaAddress).param("purpose", purpose)
                .param("types", array(hiTypes)).param("from", ts(from)).param("to", ts(to)).param("erase", ts(eraseAt))
                .param("by", requestedBy).query(UUID.class).single();
    }

    public Optional<ConsentRequest> requestByGatewayRequestId(UUID gatewayRequestId) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                FROM abdm.hiu_consent_request r JOIN core.staff s ON s.id = r.requested_by
                WHERE r.gateway_request_id = :g FOR UPDATE OF r
                """).param("g", gatewayRequestId).query(this::mapRequest).optional();
    }

    public Optional<ConsentRequest> requestByConsentRequestId(UUID consentRequestId) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                FROM abdm.hiu_consent_request r JOIN core.staff s ON s.id = r.requested_by
                WHERE r.consent_request_id = :c FOR UPDATE OF r
                """).param("c", consentRequestId).query(this::mapRequest).optional();
    }

    public Optional<ConsentRequest> request(UUID id) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                FROM abdm.hiu_consent_request r JOIN core.staff s ON s.id = r.requested_by WHERE r.id = :id
                """).param("id", id).query(this::mapRequest).optional();
    }

    public List<ConsentRequest> requestsForPatient(UUID patientId) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                FROM abdm.hiu_consent_request r JOIN core.staff s ON s.id = r.requested_by
                WHERE r.patient_id = :p ORDER BY r.requested_at DESC LIMIT 50
                """).param("p", patientId).query(this::mapRequest).list();
    }

    public void acceptConsentRequest(UUID id, UUID consentRequestId) {
        jdbc.sql("UPDATE abdm.hiu_consent_request SET consent_request_id = :c WHERE id = :id")
                .param("c", consentRequestId).param("id", id).update();
    }

    public void setRequestStatus(UUID id, String status, String error) {
        jdbc.sql("UPDATE abdm.hiu_consent_request SET status = :s, error_message = :e WHERE id = :id")
                .param("s", status).param("e", truncate(error, 300)).param("id", id).update();
    }

    // ------------------------------------------------------------------ artefacts

    /** Records an artefact ABDM named; a later status for the same id overwrites the earlier one. */
    public void upsertArtefact(UUID consentId, UUID requestRowId, String status) {
        jdbc.sql("""
                INSERT INTO abdm.hiu_consent_artefact (consent_id, consent_request_id, status) VALUES (:c, :r, :s)
                ON CONFLICT (consent_id) DO UPDATE SET status = EXCLUDED.status
                """).param("c", consentId).param("r", requestRowId).param("s", status).update();
    }

    public Optional<Artefact> artefact(UUID consentId) {
        return jdbc.sql("SELECT " + ARTEFACT_COLUMNS + """
                FROM abdm.hiu_consent_artefact a JOIN abdm.hiu_consent_request r ON r.id = a.consent_request_id
                WHERE a.consent_id = :c
                """).param("c", consentId).query(this::mapArtefact).optional();
    }

    public List<Artefact> artefactsForRequests(Collection<UUID> requestRowIds) {
        if (requestRowIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + ARTEFACT_COLUMNS + """
                FROM abdm.hiu_consent_artefact a JOIN abdm.hiu_consent_request r ON r.id = a.consent_request_id
                WHERE a.consent_request_id IN (:ids) ORDER BY a.created_at
                """).param("ids", List.copyOf(requestRowIds)).query(this::mapArtefact).list();
    }

    public List<UUID> artefactIds(UUID requestRowId) {
        return jdbc.sql("SELECT consent_id FROM abdm.hiu_consent_artefact WHERE consent_request_id = :r")
                .param("r", requestRowId).query(UUID.class).list();
    }

    public void storeArtefactDetail(UUID consentId, String status, String hipId, String hipName, String careContextsJson,
                                    List<String> hiTypes, Instant from, Instant to, Instant eraseAt, Instant fetchedAt) {
        jdbc.sql("""
                UPDATE abdm.hiu_consent_artefact
                SET status = :s, hip_id = :hip, hip_name = :hipName, care_contexts = CAST(:cc AS jsonb),
                    hi_types = CAST(:types AS varchar[]), date_range_from = :from, date_range_to = :to,
                    data_erase_at = :erase, fetched_at = :at
                WHERE consent_id = :c
                """).param("s", status).param("hip", truncate(hipId, 64)).param("hipName", truncate(hipName, 200))
                .param("cc", careContextsJson).param("types", hiTypes == null ? null : array(hiTypes))
                .param("from", ts(from)).param("to", ts(to)).param("erase", ts(eraseAt)).param("at", ts(fetchedAt))
                .param("c", consentId).update();
    }

    public int setArtefactStatus(Collection<UUID> consentIds, String status) {
        if (consentIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE abdm.hiu_consent_artefact SET status = :s WHERE consent_id IN (:ids) AND status <> :s")
                .param("s", status).param("ids", List.copyOf(consentIds)).update();
    }

    // ------------------------------------------------------------------ data requests

    public UUID insertDataRequest(UUID gatewayRequestId, UUID consentId, String pushTokenSha256, Instant from, Instant to) {
        return jdbc.sql("""
                INSERT INTO abdm.hiu_data_request (gateway_request_id, consent_id, push_token_sha256, date_range_from,
                                                   date_range_to)
                VALUES (:g, :c, :t, :from, :to) RETURNING id
                """).param("g", gatewayRequestId).param("c", consentId).param("t", pushTokenSha256)
                .param("from", ts(from)).param("to", ts(to)).query(UUID.class).single();
    }

    public Optional<DataRequest> dataRequestByGatewayRequestId(UUID gatewayRequestId) {
        return jdbc.sql("SELECT " + DATA_COLUMNS + " FROM abdm.hiu_data_request WHERE gateway_request_id = :g FOR UPDATE")
                .param("g", gatewayRequestId).query(this::mapDataRequest).optional();
    }

    /** The request a push URL token belongs to, locked so pages of one transfer are taken one at a time. */
    public Optional<DataRequest> dataRequestByToken(String pushTokenSha256) {
        return jdbc.sql("SELECT " + DATA_COLUMNS + " FROM abdm.hiu_data_request WHERE push_token_sha256 = :t FOR UPDATE")
                .param("t", pushTokenSha256).query(this::mapDataRequest).optional();
    }

    public List<DataRequest> dataRequests(Collection<UUID> consentIds) {
        if (consentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + DATA_COLUMNS + " FROM abdm.hiu_data_request WHERE consent_id IN (:ids) ORDER BY requested_at")
                .param("ids", List.copyOf(consentIds)).query(this::mapDataRequest).list();
    }

    public void acknowledgeDataRequest(UUID id, UUID transactionId) {
        jdbc.sql("""
                UPDATE abdm.hiu_data_request SET transaction_id = :t,
                       status = CASE WHEN status = 'REQUESTED' THEN 'ACKNOWLEDGED' ELSE status END
                WHERE id = :id
                """).param("t", transactionId).param("id", id).update();
    }

    public void pageReceived(UUID id, int pageCount) {
        jdbc.sql("""
                UPDATE abdm.hiu_data_request SET page_count = :n, pages_received = pages_received + 1,
                       status = CASE WHEN status = 'REQUESTED' THEN 'ACKNOWLEDGED' ELSE status END
                WHERE id = :id
                """).param("n", pageCount).param("id", id).update();
    }

    public void finishDataRequest(UUID id, String status, String error, Instant at) {
        jdbc.sql("UPDATE abdm.hiu_data_request SET status = :s, error_message = :e, completed_at = :at WHERE id = :id")
                .param("s", status).param("e", truncate(error, 300)).param("at", ts(at)).param("id", id).update();
    }

    /** Adds {@code careContextReference -> problem} entries (a JSON object) to the request's failures. */
    public void addFailures(UUID id, String failuresJson) {
        jdbc.sql("UPDATE abdm.hiu_data_request SET failures = failures || CAST(:f AS jsonb) WHERE id = :id")
                .param("f", failuresJson).param("id", id).update();
    }

    public String failures(UUID id) {
        return jdbc.sql("SELECT failures::text FROM abdm.hiu_data_request WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    /** Care contexts at least one record was accepted for. */
    public List<String> receivedReferences(UUID dataRequestId) {
        return jdbc.sql("""
                SELECT DISTINCT care_context_reference FROM abdm.hiu_health_record WHERE data_request_id = :d ORDER BY 1
                """).param("d", dataRequestId).query(String.class).list();
    }

    public Optional<DataRequest> dataRequest(UUID id) {
        return jdbc.sql("SELECT " + DATA_COLUMNS + " FROM abdm.hiu_data_request WHERE id = :id")
                .param("id", id).query(this::mapDataRequest).optional();
    }

    /** Open requests not completed in time (the HIP never pushed, or the key expired first). */
    public List<UUID> staleDataRequests(Instant requestedBefore) {
        return jdbc.sql("""
                SELECT id FROM abdm.hiu_data_request
                WHERE status IN ('REQUESTED', 'ACKNOWLEDGED') AND requested_at < :t LIMIT 100
                """).param("t", ts(requestedBefore)).query(UUID.class).list();
    }

    // ------------------------------------------------------------------ records

    /** @return true when stored (false: the same document was already received in this transfer) */
    public boolean insertRecord(NewRecord r) {
        return jdbc.sql("""
                INSERT INTO abdm.hiu_health_record (data_request_id, consent_id, patient_id, care_context_reference,
                                                    page_number, record_type, title, record_date, bundle, bundle_sha256,
                                                    data_erase_at)
                VALUES (:d, :c, :p, :cc, :page, :type, :title, :date, CAST(:bundle AS jsonb), :sha, :erase)
                ON CONFLICT (data_request_id, bundle_sha256) DO NOTHING
                """).param("d", r.dataRequestId()).param("c", r.consentId()).param("p", r.patientId())
                .param("cc", truncate(r.careContextReference(), 100)).param("page", r.pageNumber())
                .param("type", truncate(r.recordType(), 80)).param("title", truncate(r.title(), 200))
                .param("date", ts(r.recordDate())).param("bundle", r.bundleJson()).param("sha", r.sha256())
                .param("erase", ts(r.eraseAt())).update() == 1;
    }

    public List<StoredRecord> recordsForPatient(UUID patientId, Instant now) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + """
                FROM abdm.hiu_health_record h JOIN abdm.hiu_consent_artefact a ON a.consent_id = h.consent_id
                WHERE h.patient_id = :p AND h.data_erase_at > :now
                ORDER BY h.record_date DESC NULLS LAST, h.received_at DESC LIMIT 200
                """).param("p", patientId).param("now", ts(now)).query(this::mapRecord).list();
    }

    public int recordCount(UUID dataRequestId) {
        return jdbc.sql("SELECT count(*) FROM abdm.hiu_health_record WHERE data_request_id = :d")
                .param("d", dataRequestId).query(Integer.class).single();
    }

    public Optional<StoredRecord> record(UUID id, Instant now) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + """
                FROM abdm.hiu_health_record h JOIN abdm.hiu_consent_artefact a ON a.consent_id = h.consent_id
                WHERE h.id = :id AND h.data_erase_at > :now
                """).param("id", id).param("now", ts(now)).query(this::mapRecord).optional();
    }

    public String bundle(UUID id) {
        return jdbc.sql("SELECT bundle::text FROM abdm.hiu_health_record WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    public int eraseRecords(Collection<UUID> consentIds) {
        if (consentIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM abdm.hiu_health_record WHERE consent_id IN (:ids)")
                .param("ids", List.copyOf(consentIds)).update();
    }

    /** Deletes records past their erase time and marks those artefacts expired; returns records deleted. */
    public int eraseExpired(Instant now) {
        int deleted = jdbc.sql("DELETE FROM abdm.hiu_health_record WHERE data_erase_at <= :now")
                .param("now", ts(now)).update();
        jdbc.sql("UPDATE abdm.hiu_consent_artefact SET status = 'EXPIRED' WHERE status = 'GRANTED' AND data_erase_at <= :now")
                .param("now", ts(now)).update();
        return deleted;
    }

    // ------------------------------------------------------------------ mapping

    private ConsentRequest mapRequest(ResultSet rs, int n) throws SQLException {
        return new ConsentRequest(rs.getObject("id", UUID.class), rs.getObject("gateway_request_id", UUID.class),
                rs.getObject("consent_request_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getString("abha_address"), rs.getString("purpose_code"), strings(rs.getArray("hi_types")),
                instant(rs, "date_range_from"), instant(rs, "date_range_to"), instant(rs, "data_erase_at"),
                rs.getObject("requested_by", UUID.class), rs.getString("requester"), rs.getString("status"),
                rs.getString("error_message"), instant(rs, "requested_at"));
    }

    private Artefact mapArtefact(ResultSet rs, int n) throws SQLException {
        return new Artefact(rs.getObject("consent_id", UUID.class), rs.getObject("consent_request_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("status"), rs.getString("hip_id"),
                rs.getString("hip_name"), strings(rs.getArray("hi_types")), instant(rs, "date_range_from"),
                instant(rs, "date_range_to"), instant(rs, "data_erase_at"), instant(rs, "fetched_at"));
    }

    private DataRequest mapDataRequest(ResultSet rs, int n) throws SQLException {
        Object pages = rs.getObject("page_count");
        return new DataRequest(rs.getObject("id", UUID.class), rs.getObject("gateway_request_id", UUID.class),
                rs.getObject("consent_id", UUID.class), rs.getObject("transaction_id", UUID.class),
                instant(rs, "date_range_from"), instant(rs, "date_range_to"), rs.getString("status"),
                pages == null ? null : ((Number) pages).intValue(), rs.getInt("pages_received"),
                rs.getString("error_message"), instant(rs, "requested_at"), instant(rs, "completed_at"));
    }

    private StoredRecord mapRecord(ResultSet rs, int n) throws SQLException {
        return new StoredRecord(rs.getObject("id", UUID.class), rs.getObject("data_request_id", UUID.class),
                rs.getObject("consent_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getString("care_context_reference"), rs.getString("record_type"), rs.getString("title"),
                instant(rs, "record_date"), rs.getString("hip_id"), rs.getString("hip_name"),
                instant(rs, "received_at"), instant(rs, "data_erase_at"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static String array(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
