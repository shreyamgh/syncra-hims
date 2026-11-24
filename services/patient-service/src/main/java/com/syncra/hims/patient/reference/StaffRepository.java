package com.syncra.hims.patient.reference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByIamSubjectAndActiveTrue(String iamSubject);

    List<Staff> findByDepartment_CodeAndStaffRoleAndActiveTrueOrderByFullName(String departmentCode, Staff.Role role);
}
