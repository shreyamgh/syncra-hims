package com.syncra.hims.patient.emr.critical;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.patient.emr.orders.OrderRepository.ResultValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CriticalResultRepository {

    /** A critical result with its communication and acknowledgement (absent fields: not done yet). */
    public record CriticalResult(UUID id, UUID orderId, UUID patientId, UUID encounterId, String uhid, String patientName,
                                 String serviceName, List<ResultValue> criticalValues, String reportedBy,
                                 Instant reportedAt, String orderedBy, UUID orderedById, String communicatedTo,
                                 String communicationMethod, String communicatedBy, Instant communicatedAt,
                                 String acknowledgedBy, Instant acknowledgedAt, String acknowledgementNote) {

        /** TO_COMMUNICATE, then COMMUNICATED, then ACKNOWLEDGED (a doctor may acknowledge before the call). */
        @JsonProperty("status")
        public String status() {
            return acknowledgedAt != null ? "ACKNOWLEDGED" : communicatedAt != null ? "COMMUNICATED" : "TO_COMMUNICATE";
        }
    }

    private static final String SELECT = """
            SELECT c.id, c.service_order_id, c.patient_id, o.encounter_id, p.uhid, p.full_name, s.name AS service_name,
                   c.critical_values, rb.full_name AS reported_by, c.reported_at, ob.full_name AS ordered_by,
                   o.ordered_by AS ordered_by_id, c.communicated_to, c.communication_method,
                   cb.full_name AS communicated_by, c.communicated_at, ab.full_name AS acknowledged_by,
                   c.acknowledged_at, c.acknowledgement_note
            FROM clinical.critical_result c
            JOIN clinical.service_order o ON o.id = c.service_order_id
            JOIN billing.service s ON s.id = o.service_id
            JOIN patient.patient p ON p.id = c.patient_id
            JOIN core.staff rb ON rb.id = c.reported_by
            JOIN core.staff ob ON ob.id = o.ordered_by
            LEFT JOIN core.staff cb ON cb.id = c.communicated_by
            LEFT JOIN core.staff ab ON ab.id = c.acknowledged_by
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public CriticalResultRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID open(UUID orderId, UUID patientId, List<ResultValue> values, UUID reportedBy, Instant at) {
        return jdbc.sql("""
                INSERT INTO clinical.critical_result (service_order_id, patient_id, critical_values, reported_by, reported_at)
                VALUES (:o, :p, CAST(:v AS jsonb), :by, :at) RETURNING id
                """).param("o", orderId).param("p", patientId).param("v", write(values)).param("by", reportedBy)
                .param("at", Timestamp.from(at)).query(UUID.class).single();
    }

    public Optional<CriticalResult> lock(UUID id) {
        jdbc.sql("SELECT 1 FROM clinical.critical_result WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class)
                .optional();
        return find(id);
    }

    public Optional<CriticalResult> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(this::map).optional();
    }

    /**
     * Critical results by state: {@code communicated}/{@code acknowledged} filter when given,
     * {@code orderedBy} limits to one doctor's orders, {@code encounterId} to one visit.
     */
    public List<CriticalResult> list(Boolean communicated, Boolean acknowledged, UUID orderedBy, UUID encounterId) {
        return jdbc.sql(SELECT + """
                 WHERE (CAST(:comm AS boolean) IS NULL OR (c.communicated_at IS NOT NULL) = :comm)
                   AND (CAST(:ack AS boolean) IS NULL OR (c.acknowledged_at IS NOT NULL) = :ack)
                   AND (CAST(:by AS uuid) IS NULL OR o.ordered_by = :by)
                   AND (CAST(:enc AS uuid) IS NULL OR o.encounter_id = :enc)
                ORDER BY c.reported_at DESC
                LIMIT 200
                """).param("comm", communicated).param("ack", acknowledged).param("by", orderedBy)
                .param("enc", encounterId).query(this::map).list();
    }

    public void communicate(UUID id, String to, String method, UUID by, Instant at) {
        jdbc.sql("""
                UPDATE clinical.critical_result
                SET communicated_to = :to, communication_method = :m, read_back = true, communicated_by = :by,
                    communicated_at = :at
                WHERE id = :id
                """).param("to", to).param("m", method).param("by", by).param("at", Timestamp.from(at)).param("id", id)
                .update();
    }

    public void acknowledge(UUID id, UUID by, Instant at, String note) {
        jdbc.sql("""
                UPDATE clinical.critical_result SET acknowledged_by = :by, acknowledged_at = :at, acknowledgement_note = :n
                WHERE id = :id
                """).param("by", by).param("at", Timestamp.from(at)).param("n", note).param("id", id).update();
    }

    private CriticalResult map(ResultSet rs, int row) throws SQLException {
        return new CriticalResult(rs.getObject("id", UUID.class), rs.getObject("service_order_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                rs.getString("service_name"), read(rs.getString("critical_values")), rs.getString("reported_by"),
                instant(rs, "reported_at"), rs.getString("ordered_by"), rs.getObject("ordered_by_id", UUID.class),
                rs.getString("communicated_to"), rs.getString("communication_method"), rs.getString("communicated_by"),
                instant(rs, "communicated_at"), rs.getString("acknowledged_by"), instant(rs, "acknowledged_at"),
                rs.getString("acknowledgement_note"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private String write(List<ResultValue> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserialisable critical values", e);
        }
    }

    private List<ResultValue> read(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable critical values", e);
        }
    }
}
