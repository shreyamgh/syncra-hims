package com.syncra.hims.patient.registration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByUhid(String uhid);
}
