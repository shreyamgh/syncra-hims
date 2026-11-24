package com.syncra.hims.patient.ipd;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    /** Serialises bed moves and discharge for one admission. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Admission a WHERE a.id = :id")
    Optional<Admission> lockById(@Param("id") UUID id);

    @Query("""
            SELECT a FROM Admission a
              JOIN FETCH a.patient JOIN FETCH a.department JOIN FETCH a.admittingDoctor JOIN FETCH a.encounter
            WHERE a.id = :id
            """)
    Optional<Admission> findDetailed(@Param("id") UUID id);

    Optional<Admission> findFirstByPatient_IdAndStatusIn(UUID patientId, Collection<AdmissionStatus> statuses);

    @Query("""
            SELECT a FROM Admission a
              JOIN FETCH a.patient JOIN FETCH a.department JOIN FETCH a.admittingDoctor JOIN FETCH a.encounter
            WHERE a.status IN :statuses
            ORDER BY a.admittedAt
            """)
    List<Admission> findByStatuses(@Param("statuses") Collection<AdmissionStatus> statuses);
}
