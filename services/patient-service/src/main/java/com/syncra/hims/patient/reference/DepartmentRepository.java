package com.syncra.hims.patient.reference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByCodeAndActiveTrue(String code);

    List<Department> findByOpdEnabledTrueAndActiveTrueOrderByName();
}
