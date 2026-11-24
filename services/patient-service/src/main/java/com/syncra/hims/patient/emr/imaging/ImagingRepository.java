package com.syncra.hims.patient.emr.imaging;

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

/** {@code imaging.study} (the PACS index) and the sync cursor. */
@Repository
public class ImagingRepository {

    public record Study(UUID id, UUID patientId, String uhid, String patientName, UUID orderId, String serviceName,
                        UUID encounterId, String studyInstanceUid, String accessionNumber, String modality,
                        LocalDate studyDate, String description, Integer seriesCount, Integer instanceCount,
                        String reportStatus, UUID reportNoteId, String reportedBy, Instant reportedAt, Instant receivedAt,
                        String clinicalIndication, String priority, String orderedBy, String reportContent) {

        /** DICOMweb location of the study through the gateway (the pixel data never leaves Orthanc). */
        @com.fasterxml.jackson.annotation.JsonProperty("dicomWebPath")
        public String dicomWebPath() {
            return "/pacs/dicom-web/studies/" + studyInstanceUid;
        }
    }

    public record Incoming(UUID patientId, UUID orderId, String studyInstanceUid, String accessionNumber,
                           String orthancId, String modality, LocalDate studyDate, String description,
                           Integer seriesCount, Integer instanceCount) {
    }

    private static final String SELECT = """
            SELECT st.id, st.patient_id, p.uhid, p.full_name, st.service_order_id, s.name AS service_name, o.encounter_id,
                   st.study_instance_uid, st.accession_number, st.modality, st.study_date, st.study_description,
                   st.series_count, st.instance_count, st.report_status, st.report_note_id, rb.full_name AS reported_by,
                   st.reported_at, st.received_at, o.clinical_indication, o.priority, ob.full_name AS ordered_by,
                   rn.content AS report_content
            FROM imaging.study st
            JOIN patient.patient p ON p.id = st.patient_id
            LEFT JOIN clinical.service_order o ON o.id = st.service_order_id
            LEFT JOIN billing.service s ON s.id = o.service_id
            LEFT JOIN core.staff rb ON rb.id = st.reported_by
            LEFT JOIN core.staff ob ON ob.id = o.ordered_by
            LEFT JOIN clinical.clinical_note rn ON rn.id = st.report_note_id
            """;

    private final JdbcClient jdbc;

    public ImagingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long cursor() {
        return jdbc.sql("SELECT last_change FROM imaging.pacs_sync WHERE id = 1").query(Long.class).optional().orElse(0L);
    }

    public void advance(long lastChange, Instant at) {
        jdbc.sql("UPDATE imaging.pacs_sync SET last_change = :c, synced_at = :at WHERE id = 1 AND last_change <= :c")
                .param("c", lastChange).param("at", Timestamp.from(at)).update();
    }

    public Optional<UUID> patientByUhid(String uhid) {
        return jdbc.sql("SELECT id FROM patient.patient WHERE uhid = :u AND merged_into_id IS NULL")
                .param("u", uhid).query(UUID.class).optional();
    }

    public Optional<UUID> radiologyOrder(UUID patientId, String accession) {
        return jdbc.sql("""
                SELECT id FROM clinical.service_order
                WHERE patient_id = :p AND accession_number = :a AND order_type = 'RADIOLOGY' AND status <> 'CANCELLED'
                """).param("p", patientId).param("a", accession).query(UUID.class).optional();
    }

    /** Adds or refreshes a study; an order already matched by hand is kept. */
    public UUID upsert(Incoming s) {
        return jdbc.sql("""
                INSERT INTO imaging.study (patient_id, service_order_id, study_instance_uid, accession_number,
                                           orthanc_study_id, modality, study_date, study_description, series_count,
                                           instance_count)
                VALUES (:p, :o, :uid, :acc, :orthanc, :modality, :date, :description, :series, :instances)
                ON CONFLICT (study_instance_uid) DO UPDATE
                SET service_order_id = COALESCE(imaging.study.service_order_id, EXCLUDED.service_order_id),
                    orthanc_study_id = EXCLUDED.orthanc_study_id, modality = EXCLUDED.modality,
                    series_count = EXCLUDED.series_count, instance_count = EXCLUDED.instance_count
                RETURNING id
                """).param("p", s.patientId()).param("o", s.orderId()).param("uid", s.studyInstanceUid())
                .param("acc", s.accessionNumber()).param("orthanc", s.orthancId()).param("modality", s.modality())
                .param("date", s.studyDate()).param("description", s.description()).param("series", s.seriesCount())
                .param("instances", s.instanceCount()).query(UUID.class).single();
    }

    /** A matched radiology order whose images have arrived is COMPLETED (awaiting the report). */
    public void orderImaged(UUID orderId, Instant at) {
        jdbc.sql("""
                UPDATE clinical.service_order SET status = 'COMPLETED', completed_at = COALESCE(completed_at, :at),
                       version = version + 1
                WHERE id = :id AND status IN ('ORDERED', 'SCHEDULED', 'IN_PROGRESS')
                """).param("at", Timestamp.from(at)).param("id", orderId).update();
    }

    public Optional<Study> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE st.id = :id").param("id", id).query(ImagingRepository::map).optional();
    }

    public Optional<Study> lock(UUID id) {
        jdbc.sql("SELECT 1 FROM imaging.study WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return find(id);
    }

    /** Studies by report status and match; STAT and urgent first, then oldest; {@code reportedSince} for recent reports. */
    public List<Study> worklist(List<String> reportStatuses, Boolean matched, Instant reportedSince) {
        return jdbc.sql(SELECT + """
                 WHERE (CAST(:any AS boolean) OR st.report_status IN (:statuses))
                   AND (CAST(:matched AS boolean) IS NULL OR (st.service_order_id IS NOT NULL) = :matched)
                   AND (CAST(:since AS timestamptz) IS NULL OR st.reported_at >= :since)
                ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, st.received_at
                LIMIT 200
                """).param("any", reportStatuses.isEmpty())
                .param("statuses", reportStatuses.isEmpty() ? List.of("-") : reportStatuses)
                .param("matched", matched).param("since", reportedSince == null ? null : Timestamp.from(reportedSince))
                .query(ImagingRepository::map).list();
    }

    public void match(UUID studyId, UUID orderId) {
        jdbc.sql("UPDATE imaging.study SET service_order_id = :o WHERE id = :id").param("o", orderId).param("id", studyId)
                .update();
    }

    public void reported(UUID studyId, String status, UUID noteId, UUID radiologist, Instant at) {
        jdbc.sql("""
                UPDATE imaging.study SET report_status = :s, report_note_id = :note, reported_by = :by, reported_at = :at
                WHERE id = :id
                """).param("s", status).param("note", noteId).param("by", radiologist).param("at", Timestamp.from(at))
                .param("id", studyId).update();
    }

    private static Study map(ResultSet rs, int row) throws SQLException {
        Timestamp reported = rs.getTimestamp("reported_at");
        return new Study(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("uhid"),
                rs.getString("full_name"), rs.getObject("service_order_id", UUID.class), rs.getString("service_name"),
                rs.getObject("encounter_id", UUID.class), rs.getString("study_instance_uid"),
                rs.getString("accession_number"), rs.getString("modality"),
                rs.getDate("study_date") == null ? null : rs.getDate("study_date").toLocalDate(),
                rs.getString("study_description"), (Integer) rs.getObject("series_count"),
                (Integer) rs.getObject("instance_count"), rs.getString("report_status"),
                rs.getObject("report_note_id", UUID.class), rs.getString("reported_by"),
                reported == null ? null : reported.toInstant(), rs.getTimestamp("received_at").toInstant(),
                rs.getString("clinical_indication"), rs.getString("priority"), rs.getString("ordered_by"),
                rs.getString("report_content"));
    }
}
