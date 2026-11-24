package com.syncra.hims.patient.ipd;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BedRepository extends JpaRepository<Bed, UUID> {

    @Query("SELECT b FROM Bed b JOIN FETCH b.ward WHERE b.bedCode = :code AND b.active = true")
    Optional<Bed> findActiveByCode(@Param("code") String code);

    /** Current status straight from the database (bypasses the persistence context). */
    @Query(value = "SELECT status FROM ipd.bed WHERE bed_code = :code", nativeQuery = true)
    Optional<String> currentStatus(@Param("code") String code);

    /**
     * Compare-and-set status change: succeeds only if the bed is still in one of
     * the expected states, so housekeeping can never overwrite a concurrent
     * admission's OCCUPIED.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE ipd.bed SET status = :target, version = version + 1
            WHERE bed_code = :code AND is_active AND status IN (:expected)
            """, nativeQuery = true)
    int compareAndSetStatus(@Param("code") String code,
                            @Param("target") String target,
                            @Param("expected") Collection<String> expected);
}
