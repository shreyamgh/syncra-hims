package com.syncra.hims.patient.emr.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code clinical.service_order}: investigation orders, their samples and results. */
@Repository
public class OrderRepository {

    /** One measured value; {@code flag} H high, L low, N normal, A abnormal, C critical. */
    public record ResultValue(String code, String name, BigDecimal value, String text, String unit,
                              String referenceRange, String flag) {
    }

    public record Order(UUID id, UUID encounterId, UUID patientId, String uhid, String patientName, String serviceCode,
                        String serviceName, String orderType, String status, String priority, String clinicalIndication,
                        String accessionNumber, String orderedBy, Instant orderedAt, Instant sampleCollectedAt,
                        Instant completedAt, String resultSummary, List<ResultValue> resultValues, String reportedBy,
                        String cancelledReason, String studyInstanceUid) {
    }

    public record Orderable(UUID serviceId, String code, String name, String category, String modality) {
    }

    /** A recent structured creatinine result, for the renal guard. */
    public record Creatinine(BigDecimal mgPerDl, Instant at) {
    }

    private static final String SELECT = """
            SELECT o.id, o.encounter_id, o.patient_id, p.uhid, p.full_name, s.code, s.name, o.order_type, o.status,
                   o.priority, o.clinical_indication, o.accession_number, ob.full_name AS ordered_by, o.ordered_at,
                   o.sample_collected_at, o.completed_at, o.result_summary, o.result_values,
                   rb.full_name AS reported_by, o.cancelled_reason,
                   (SELECT st.study_instance_uid FROM imaging.study st WHERE st.service_order_id = o.id
                    ORDER BY st.received_at DESC LIMIT 1) AS study_uid
            FROM clinical.service_order o
            JOIN billing.service s ON s.id = o.service_id
            JOIN patient.patient p ON p.id = o.patient_id
            JOIN core.staff ob ON ob.id = o.ordered_by
            LEFT JOIN core.staff rb ON rb.id = o.reported_by
            """;
    private static final TypeReference<List<ResultValue>> VALUES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OrderRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Orderable> orderable(String code) {
        return jdbc.sql("""
                SELECT id, code, name, category, dicom_modality FROM billing.service
                WHERE code = :code AND is_active
                """).param("code", code)
                .query((rs, n) -> new Orderable(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getString("dicom_modality"))).optional();
    }

    /** Active laboratory, radiology and procedure services whose code or name contains {@code query}. */
    public List<Orderable> searchOrderables(String query, String category, int limit) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.sql("""
                SELECT id, code, name, category, dicom_modality FROM billing.service
                WHERE is_active AND category IN ('LABORATORY', 'RADIOLOGY', 'PROCEDURE')
                  AND (CAST(:category AS varchar) IS NULL OR category = :category)
                  AND (name ILIKE :contains OR code ILIKE :contains)
                ORDER BY (code ILIKE :prefix OR name ILIKE :prefix) DESC, name
                LIMIT :limit
                """).param("category", category).param("contains", "%" + escaped + "%")
                .param("prefix", escaped + "%").param("limit", limit)
                .query((rs, n) -> new Orderable(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("category"), rs.getString("dicom_modality"))).list();
    }

    public UUID insert(UUID encounterId, UUID patientId, Orderable service, String orderType, String priority,
                       String indication, UUID orderedBy) {
        return jdbc.sql("""
                INSERT INTO clinical.service_order (encounter_id, patient_id, service_id, order_type, priority,
                                                    clinical_indication, ordered_by, accession_number)
                VALUES (:e, :p, :s, :type, :priority, :indication, :by,
                        CASE WHEN :type = 'RADIOLOGY' THEN core.next_document_number('RAD') END)
                RETURNING id
                """).param("e", encounterId).param("p", patientId).param("s", service.serviceId()).param("type", orderType)
                .param("priority", priority).param("indication", indication).param("by", orderedBy)
                .query(UUID.class).single();
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Order> lock(UUID id) {
        jdbc.sql("SELECT 1 FROM clinical.service_order WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return find(id);
    }

    public List<Order> forEncounter(UUID encounterId) {
        return jdbc.sql(SELECT + " WHERE o.encounter_id = :id ORDER BY o.ordered_at").param("id", encounterId)
                .query(this::map).list();
    }

    /** Orders of a type in the given statuses, most urgent first, then oldest; {@code since} limits by last activity. */
    public List<Order> worklist(String orderType, List<String> statuses, Instant since) {
        return jdbc.sql(SELECT + """
                 WHERE o.order_type = :type AND o.status IN (:statuses)
                   AND (CAST(:since AS timestamptz) IS NULL OR COALESCE(o.completed_at, o.ordered_at) >= :since)
                ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, o.ordered_at
                LIMIT 200
                """).param("type", orderType).param("statuses", statuses)
                .param("since", since == null ? null : Timestamp.from(since)).query(this::map).list();
    }

    /** The active parameters of a lab test, in report order. */
    public List<LabRange.Analyte> analytes(String serviceCode) {
        return jdbc.sql("""
                SELECT a.code, a.name, a.unit, a.decimals, a.ref_low, a.ref_high, a.ref_low_female, a.ref_high_female,
                       a.critical_low, a.critical_high
                FROM clinical.lab_analyte a JOIN billing.service s ON s.id = a.service_id
                WHERE s.code = :code AND a.is_active
                ORDER BY a.display_order, a.name
                """).param("code", serviceCode)
                .query((rs, n) -> new LabRange.Analyte(rs.getString("code"), rs.getString("name"), rs.getString("unit"),
                        rs.getInt("decimals"), rs.getBigDecimal("ref_low"), rs.getBigDecimal("ref_high"),
                        rs.getBigDecimal("ref_low_female"), rs.getBigDecimal("ref_high_female"),
                        rs.getBigDecimal("critical_low"), rs.getBigDecimal("critical_high"))).list();
    }

    /** Sex and date of birth, which select the reference interval. */
    public record PatientFacts(String sex, LocalDate dateOfBirth) {
    }

    public PatientFacts patientFacts(UUID patientId) {
        return jdbc.sql("SELECT gender, date_of_birth FROM patient.patient WHERE id = :id").param("id", patientId)
                .query((rs, n) -> new PatientFacts(rs.getString("gender"),
                        rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate()))
                .single();
    }

    public boolean isBilled(UUID orderId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM billing.bill_item i JOIN billing.bill b ON b.id = i.bill_id
                               WHERE i.source_type = 'SERVICE_ORDER' AND i.source_id = :id AND b.status <> 'CANCELLED')
                """).param("id", orderId).query(Boolean.class).single();
    }

    public void cancel(UUID id, String reason) {
        jdbc.sql("UPDATE clinical.service_order SET status = 'CANCELLED', cancelled_reason = :r, version = version + 1 WHERE id = :id")
                .param("r", reason).param("id", id).update();
    }

    public void sampleCollected(UUID id, Instant at) {
        jdbc.sql("""
                UPDATE clinical.service_order SET status = 'SAMPLE_COLLECTED', sample_collected_at = :at, version = version + 1
                WHERE id = :id
                """).param("at", Timestamp.from(at)).param("id", id).update();
    }

    public void report(UUID id, String summary, List<ResultValue> values, UUID reportedBy, Instant at) {
        jdbc.sql("""
                UPDATE clinical.service_order
                SET status = 'REPORTED', result_summary = :summary, result_values = CAST(:values AS jsonb),
                    reported_by = :by, completed_at = COALESCE(completed_at, :at), version = version + 1
                WHERE id = :id
                """).param("summary", summary).param("values", values == null || values.isEmpty() ? null : write(values))
                .param("by", reportedBy).param("at", Timestamp.from(at)).param("id", id).update();
    }

    /**
     * The patient's latest serum creatinine within the window: a result value with code CREAT
     * (or named creatinine), in mg/dL or umol/L (converted).
     */
    public Optional<Creatinine> latestCreatinine(UUID patientId, Instant since) {
        return jdbc.sql("""
                SELECT (v->>'value')::numeric AS value, lower(COALESCE(v->>'unit', 'mg/dl')) AS unit, o.completed_at
                FROM clinical.service_order o, jsonb_array_elements(o.result_values) v
                WHERE o.patient_id = :p AND o.status = 'REPORTED' AND o.completed_at >= :since
                  AND (upper(v->>'code') IN ('CREAT', 'CREATININE', 'SCR') OR v->>'name' ILIKE '%creatinine%')
                  AND jsonb_typeof(v->'value') = 'number'
                ORDER BY o.completed_at DESC LIMIT 1
                """).param("p", patientId).param("since", Timestamp.from(since))
                .query((rs, n) -> {
                    BigDecimal value = rs.getBigDecimal("value");
                    String unit = rs.getString("unit");
                    BigDecimal mg = unit.contains("mol") ? value.divide(BigDecimal.valueOf(88.42), 2,
                            java.math.RoundingMode.HALF_UP) : value;
                    return new Creatinine(mg, rs.getTimestamp("completed_at").toInstant());
                }).optional();
    }

    private String write(List<ResultValue> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserialisable result values", e);
        }
    }

    private Order map(ResultSet rs, int row) throws SQLException {
        String values = rs.getString("result_values");
        List<ResultValue> parsed;
        try {
            parsed = values == null ? List.of() : json.readValue(values, VALUES);
        } catch (JsonProcessingException e) {
            parsed = List.of();
        }
        return new Order(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                rs.getString("code"), rs.getString("name"), rs.getString("order_type"), rs.getString("status"),
                rs.getString("priority"), rs.getString("clinical_indication"), rs.getString("accession_number"),
                rs.getString("ordered_by"), instant(rs, "ordered_at"), instant(rs, "sample_collected_at"),
                instant(rs, "completed_at"), rs.getString("result_summary"), parsed, rs.getString("reported_by"),
                rs.getString("cancelled_reason"), rs.getString("study_uid"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
