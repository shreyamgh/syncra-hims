package com.syncra.hims.billing.bill;

import com.syncra.hims.billing.bill.BedDayCalculator.Stay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only view of the clinical/administrative facts billing depends on (owned by
 * the Patient Service): visits, admissions, bed stays and investigation orders.
 */
@Repository
public class CareContext {

    public record Visit(UUID visitId, UUID encounterId, UUID patientId, String encounterType, String visitType,
                        String queueStatus, LocalDate visitDate, int tokenNumber, String departmentName) {
    }

    public record Admission(UUID admissionId, String admissionNumber, UUID encounterId, UUID patientId,
                            UUID tariffPlanId, Instant admittedAt, String status, Instant dischargedAt,
                            String departmentName) {
        public boolean isActive() {
            return "ADMITTED".equals(status) || "DISCHARGE_INITIATED".equals(status);
        }
    }

    public record Patient(UUID id, String uhid, String name, String gender, LocalDate dateOfBirth, String mobile,
                          String address) {
    }

    public record Order(UUID orderId, String serviceCode, String serviceName, Instant orderedAt) {
    }

    private final JdbcClient jdbc;

    public CareContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Visit> visit(UUID visitId) {
        return jdbc.sql("""
                SELECT v.id, v.encounter_id, v.patient_id, e.encounter_type, v.visit_type, v.queue_status,
                       v.visit_date, v.token_number, d.name AS department
                FROM opd.visit AS v
                JOIN clinical.encounter AS e ON e.id = v.encounter_id
                JOIN core.department AS d ON d.id = v.department_id
                WHERE v.id = :id
                """)
                .param("id", visitId)
                .query((rs, n) -> new Visit(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("encounter_type"),
                        rs.getString("visit_type"), rs.getString("queue_status"),
                        rs.getObject("visit_date", LocalDate.class), rs.getInt("token_number"),
                        rs.getString("department")))
                .optional();
    }

    public Optional<Admission> admission(UUID admissionId) {
        return jdbc.sql("""
                SELECT a.id, a.admission_number, a.encounter_id, a.patient_id, a.tariff_plan_id, a.admitted_at,
                       a.status, a.discharged_at, d.name AS department
                FROM ipd.admission AS a
                JOIN core.department AS d ON d.id = a.department_id
                WHERE a.id = :id
                """)
                .param("id", admissionId)
                .query((rs, n) -> new Admission(rs.getObject("id", UUID.class), rs.getString("admission_number"),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("tariff_plan_id", UUID.class), instant(rs, "admitted_at"), rs.getString("status"),
                        instant(rs, "discharged_at"), rs.getString("department")))
                .optional();
    }

    public Optional<Patient> patient(UUID patientId) {
        return jdbc.sql("""
                SELECT id, uhid, full_name, gender, date_of_birth, mobile,
                       concat_ws(', ', address_line, city_village, district_name, state_name, pincode) AS address
                FROM patient.patient WHERE id = :id
                """)
                .param("id", patientId)
                .query((rs, n) -> new Patient(rs.getObject("id", UUID.class), rs.getString("uhid"),
                        rs.getString("full_name"), rs.getString("gender").trim(),
                        rs.getObject("date_of_birth", LocalDate.class), rs.getString("mobile"),
                        rs.getString("address")))
                .optional();
    }

    public List<Stay> stays(UUID admissionId) {
        return jdbc.sql("""
                SELECT ba.id, b.bed_code, ba.bed_category, ba.assigned_at, ba.released_at
                FROM ipd.bed_assignment AS ba
                JOIN ipd.bed AS b ON b.id = ba.bed_id
                WHERE ba.admission_id = :id
                ORDER BY ba.assigned_at
                """)
                .param("id", admissionId)
                .query((rs, n) -> new Stay(rs.getObject("id", UUID.class), rs.getString("bed_code"),
                        rs.getString("bed_category"), instant(rs, "assigned_at"), instant(rs, "released_at")))
                .list();
    }

    /** Ward class the patient is in now (for ward-class-priced services such as consultant visits). */
    public Optional<String> currentBedCategory(UUID admissionId) {
        return jdbc.sql("""
                SELECT bed_category FROM ipd.bed_assignment
                WHERE admission_id = :id ORDER BY released_at IS NULL DESC, assigned_at DESC LIMIT 1
                """)
                .param("id", admissionId).query(String.class).optional();
    }

    /** Investigation/procedure orders of an encounter not yet charged on a live bill. */
    public List<Order> unbilledOrders(UUID encounterId) {
        return jdbc.sql("""
                SELECT o.id, s.code, s.name, o.ordered_at
                FROM clinical.service_order AS o
                JOIN billing.service AS s ON s.id = o.service_id
                WHERE o.encounter_id = :encounter AND o.status <> 'CANCELLED'
                  AND NOT EXISTS (
                      SELECT 1 FROM billing.bill_item AS i JOIN billing.bill AS b ON b.id = i.bill_id
                      WHERE i.source_type = 'SERVICE_ORDER' AND i.source_id = o.id AND b.status <> 'CANCELLED')
                ORDER BY o.ordered_at
                """)
                .param("encounter", encounterId)
                .query((rs, n) -> new Order(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        instant(rs, "ordered_at")))
                .list();
    }

    /** Whether the patient paid a registration fee on or after {@code since} (bill not cancelled). */
    public boolean registrationPaidSince(UUID patientId, LocalDate since) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM billing.bill_item AS i
                    JOIN billing.bill AS b ON b.id = i.bill_id
                    JOIN billing.service AS s ON s.id = i.service_id
                    WHERE b.patient_id = :patient AND s.code = 'REG-OPD' AND b.status <> 'CANCELLED'
                      AND i.service_date >= :since)
                """)
                .param("patient", patientId).param("since", since).query(Boolean.class).single();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
