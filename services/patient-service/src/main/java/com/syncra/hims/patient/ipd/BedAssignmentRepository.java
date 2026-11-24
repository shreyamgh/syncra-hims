package com.syncra.hims.patient.ipd;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BedAssignmentRepository extends JpaRepository<BedAssignment, UUID> {

    @Query("""
            SELECT a FROM BedAssignment a JOIN FETCH a.bed b JOIN FETCH b.ward
            WHERE a.admission.id = :admissionId AND a.releasedAt IS NULL
            """)
    Optional<BedAssignment> findOpen(@Param("admissionId") UUID admissionId);
}
