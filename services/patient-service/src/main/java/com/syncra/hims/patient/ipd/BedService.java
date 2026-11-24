package com.syncra.hims.patient.ipd;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.ipd.dto.BedViews.AvailableBed;
import com.syncra.hims.patient.ipd.dto.BedViews.BedState;
import com.syncra.hims.patient.ipd.dto.BedViews.BoardEntry;
import com.syncra.hims.patient.ipd.dto.BedViews.Occupant;
import com.syncra.hims.patient.ipd.dto.BedViews.WardCensus;
import com.syncra.hims.patient.registration.Gender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bed board, ward census, bed finder and housekeeping state changes. */
@Service
public class BedService {

    private final JdbcClient jdbc;
    private final BedRepository beds;
    private final AuditService audit;

    public BedService(JdbcClient jdbc, BedRepository beds, AuditService audit) {
        this.jdbc = jdbc;
        this.beds = beds;
        this.audit = audit;
    }

    /** Live board from {@code ipd.v_bed_board}, optionally filtered by ward and status. */
    @Transactional(readOnly = true)
    public List<BoardEntry> board(String wardCode, BedStatus status) {
        return jdbc.sql("""
                SELECT ward_code, ward_name, ward_type, bed_code, bed_category, bed_status,
                       admission_id, admission_number, uhid, patient_name, gender, age_years, admitted_at,
                       admitting_doctor
                FROM ipd.v_bed_board
                WHERE (:ward = '' OR ward_code = :ward) AND (:status = '' OR bed_status = :status)
                ORDER BY ward_code, bed_code
                """)
                .param("ward", wardCode == null ? "" : wardCode.toUpperCase(Locale.ROOT))
                .param("status", status == null ? "" : status.name())
                .query(BedService::mapBoard)
                .list();
    }

    @Transactional(readOnly = true)
    public List<WardCensus> census() {
        return jdbc.sql("""
                SELECT w.code, w.name, w.ward_type, w.gender_restriction,
                       count(*)                                              AS total,
                       count(*) FILTER (WHERE b.status = 'AVAILABLE')        AS available,
                       count(*) FILTER (WHERE b.status = 'OCCUPIED')         AS occupied,
                       count(*) FILTER (WHERE b.status = 'RESERVED')         AS reserved,
                       count(*) FILTER (WHERE b.status = 'CLEANING')         AS cleaning,
                       count(*) FILTER (WHERE b.status IN ('MAINTENANCE', 'BLOCKED')) AS out_of_service
                FROM ipd.ward AS w
                JOIN ipd.bed AS b ON b.ward_id = w.id AND b.is_active
                WHERE w.is_active
                GROUP BY w.id
                ORDER BY w.code
                """)
                .query((rs, n) -> new WardCensus(rs.getString("code"), rs.getString("name"),
                        rs.getString("ward_type"), rs.getString("gender_restriction"), rs.getInt("total"),
                        rs.getInt("available"), rs.getInt("occupied"), rs.getInt("reserved"),
                        rs.getInt("cleaning"), rs.getInt("out_of_service")))
                .list();
    }

    /** Beds a patient of {@code gender} could be put in now, optionally of one ward class. */
    @Transactional(readOnly = true)
    public List<AvailableBed> available(Gender gender, BedCategory category) {
        return jdbc.sql("""
                SELECT b.bed_code, w.code AS ward_code, w.name AS ward_name, b.bed_category,
                       b.has_oxygen, b.has_monitor, b.has_ventilator
                FROM ipd.bed AS b
                JOIN ipd.ward AS w ON w.id = b.ward_id
                WHERE b.is_active AND w.is_active AND b.status = 'AVAILABLE'
                  AND (w.gender_restriction = 'ANY' OR w.gender_restriction = :gender)
                  AND (:category = '' OR b.bed_category = :category)
                ORDER BY b.bed_category, w.code, b.bed_code
                """)
                .param("gender", gender == null ? "ANY" : gender.name())
                .param("category", category == null ? "" : category.name())
                .query((rs, n) -> new AvailableBed(rs.getString("bed_code"), rs.getString("ward_code"),
                        rs.getString("ward_name"), BedCategory.valueOf(rs.getString("bed_category")),
                        rs.getBoolean("has_oxygen"), rs.getBoolean("has_monitor"), rs.getBoolean("has_ventilator")))
                .list();
    }

    /**
     * Housekeeping: e.g. CLEANING -> AVAILABLE, AVAILABLE -> MAINTENANCE. OCCUPIED and
     * CLEANING are never set by hand; they follow admissions and discharges.
     */
    @Transactional
    public BedState changeStatus(String rawBedCode, BedStatus target) {
        String bedCode = rawBedCode.trim().toUpperCase(Locale.ROOT);
        if (!target.isManuallySettable()) {
            throw ApiException.unprocessable("NOT_MANUAL_STATUS",
                    target + " is set automatically by admissions and discharges");
        }
        BedStatus current = beds.currentStatus(bedCode).map(BedStatus::valueOf)
                .orElseThrow(() -> ApiException.notFound("Bed", bedCode));
        if (current == target) {
            return new BedState(bedCode, current);
        }
        List<String> expected = target.manuallyReachableFrom().stream().map(Enum::name).toList();
        if (!target.manuallyReachableFrom().contains(current)
                || beds.compareAndSetStatus(bedCode, target.name(), expected) == 0) {
            BedStatus now = beds.currentStatus(bedCode).map(BedStatus::valueOf).orElse(current);
            throw ApiException.conflict("INVALID_BED_TRANSITION",
                    "Bed " + bedCode + " is " + now + " and cannot be set to " + target);
        }
        audit.record(Action.UPDATE, "bed", bedCode, null, Map.of("from", current.name(), "to", target.name()));
        return new BedState(bedCode, target);
    }

    private static BoardEntry mapBoard(ResultSet rs, int rowNum) throws SQLException {
        Occupant occupant = null;
        UUID admissionId = rs.getObject("admission_id", UUID.class);
        if (admissionId != null) {
            OffsetDateTime admittedAt = rs.getObject("admitted_at", OffsetDateTime.class);
            occupant = new Occupant(admissionId, rs.getString("admission_number"), rs.getString("uhid"),
                    rs.getString("patient_name"), Gender.valueOf(rs.getString("gender").trim()),
                    rs.getObject("age_years", Integer.class), admittedAt == null ? null : admittedAt.toInstant(),
                    rs.getString("admitting_doctor"));
        }
        return new BoardEntry(rs.getString("ward_code"), rs.getString("ward_name"), rs.getString("ward_type"),
                rs.getString("bed_code"), BedCategory.valueOf(rs.getString("bed_category")),
                BedStatus.valueOf(rs.getString("bed_status")), occupant);
    }
}
