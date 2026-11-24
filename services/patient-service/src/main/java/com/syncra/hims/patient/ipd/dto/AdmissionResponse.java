package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.Admission;
import com.syncra.hims.patient.ipd.AdmissionStatus;
import com.syncra.hims.patient.ipd.AdmissionType;
import com.syncra.hims.patient.ipd.BedAssignment;
import com.syncra.hims.patient.ipd.BedCategory;
import com.syncra.hims.patient.registration.Gender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdmissionResponse(
        UUID admissionId,
        String admissionNumber,
        UUID encounterId,
        String uhid,
        String patientName,
        Gender gender,
        Integer ageYears,
        String departmentCode,
        String departmentName,
        Doctor admittingDoctor,
        AdmissionType admissionType,
        AdmissionStatus status,
        Instant admittedAt,
        String provisionalDiagnosis,
        LocalDate expectedDischargeDate,
        CurrentBed currentBed,
        Instant dischargeInitiatedAt,
        Instant dischargedAt,
        long version) {

    public record Doctor(UUID id, String name) {
    }

    public record CurrentBed(String bedCode, String wardCode, String wardName, BedCategory bedCategory,
                             Instant assignedAt) {
        static CurrentBed of(BedAssignment a) {
            return a == null ? null : new CurrentBed(a.getBed().getBedCode(), a.getBed().getWard().getCode(),
                    a.getBed().getWard().getName(), a.getBedCategory(), a.getAssignedAt());
        }
    }

    public static AdmissionResponse from(Admission a, BedAssignment openBed, Integer ageYears) {
        return new AdmissionResponse(
                a.getId(), a.getAdmissionNumber(), a.getEncounter().getId(),
                a.getPatient().getUhid().value(), a.getPatient().getFullName(), a.getPatient().getGender(), ageYears,
                a.getDepartment().getCode(), a.getDepartment().getName(),
                new Doctor(a.getAdmittingDoctor().getId(), a.getAdmittingDoctor().getDisplayName()),
                a.getAdmissionType(), a.getStatus(), a.getAdmittedAt(), a.getProvisionalDiagnosis(),
                a.getExpectedDischargeDate(), CurrentBed.of(openBed), a.getDischargeInitiatedAt(),
                a.getDischargedAt(), a.getVersion());
    }
}
