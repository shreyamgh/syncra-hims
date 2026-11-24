package com.syncra.hims.patient.opd;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpdVisitRepository extends JpaRepository<OpdVisit, UUID> {

    @Query("""
            SELECT v FROM OpdVisit v
              JOIN FETCH v.patient JOIN FETCH v.department LEFT JOIN FETCH v.doctor
            WHERE v.id = :id
            """)
    Optional<OpdVisit> findDetailed(@Param("id") UUID id);

    /** The live token a patient already holds in a department today, if any. */
    @Query("""
            SELECT v FROM OpdVisit v
            WHERE v.patient.id = :patientId AND v.department.id = :departmentId AND v.visitDate = :date
              AND v.queueStatus IN (com.syncra.hims.patient.opd.QueueStatus.WAITING,
                                    com.syncra.hims.patient.opd.QueueStatus.CALLED,
                                    com.syncra.hims.patient.opd.QueueStatus.IN_CONSULTATION,
                                    com.syncra.hims.patient.opd.QueueStatus.ON_HOLD)
            """)
    Optional<OpdVisit> findLiveToken(@Param("patientId") UUID patientId,
                                     @Param("departmentId") UUID departmentId,
                                     @Param("date") LocalDate date);

    @Query("""
            SELECT v FROM OpdVisit v
              JOIN FETCH v.patient JOIN FETCH v.department LEFT JOIN FETCH v.doctor
            WHERE v.department.code = :departmentCode AND v.visitDate = :date
            """)
    List<OpdVisit> findQueue(@Param("departmentCode") String departmentCode, @Param("date") LocalDate date);

    /*
     * "Call next": lock the next waiting token in priority-rank then token order.
     * SKIP LOCKED lets several doctors press the button at the same moment without
     * being handed the same patient.
     */

    /** Next token for a doctor: unassigned tokens or ones already booked with them. */
    @Query(value = """
            SELECT v.* FROM opd.visit AS v
            JOIN core.department AS d ON d.id = v.department_id
            WHERE d.code = :departmentCode AND v.visit_date = :date AND v.queue_status = 'WAITING'
              AND (v.doctor_id IS NULL OR v.doctor_id = :doctorId)
            ORDER BY CASE v.priority WHEN 'EMERGENCY' THEN 0 WHEN 'NORMAL' THEN 2 ELSE 1 END, v.token_number
            LIMIT 1
            FOR UPDATE OF v SKIP LOCKED
            """, nativeQuery = true)
    Optional<OpdVisit> lockNextWaitingForDoctor(@Param("departmentCode") String departmentCode,
                                                @Param("date") LocalDate date,
                                                @Param("doctorId") UUID doctorId);

    /** Next token in the department regardless of doctor (nurse/desk calling). */
    @Query(value = """
            SELECT v.* FROM opd.visit AS v
            JOIN core.department AS d ON d.id = v.department_id
            WHERE d.code = :departmentCode AND v.visit_date = :date AND v.queue_status = 'WAITING'
            ORDER BY CASE v.priority WHEN 'EMERGENCY' THEN 0 WHEN 'NORMAL' THEN 2 ELSE 1 END, v.token_number
            LIMIT 1
            FOR UPDATE OF v SKIP LOCKED
            """, nativeQuery = true)
    Optional<OpdVisit> lockNextWaiting(@Param("departmentCode") String departmentCode,
                                       @Param("date") LocalDate date);
}
