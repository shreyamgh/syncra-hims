package com.syncra.hims.patient.opd.dto;

import com.syncra.hims.patient.opd.OpdVisit;
import com.syncra.hims.patient.opd.QueueStatus;
import com.syncra.hims.patient.opd.VisitPriority;
import com.syncra.hims.patient.opd.VisitType;
import com.syncra.hims.patient.reference.Staff;
import com.syncra.hims.patient.registration.Gender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An OPD token with the patient's identifying details for the queue screen. */
public record VisitResponse(
        UUID visitId,
        UUID encounterId,
        int tokenNumber,
        LocalDate visitDate,
        String departmentCode,
        String departmentName,
        Doctor doctor,
        String uhid,
        String patientName,
        Gender gender,
        Integer ageYears,
        VisitType visitType,
        VisitPriority priority,
        QueueStatus queueStatus,
        Instant registeredAt,
        Instant calledAt,
        Instant consultationStartedAt,
        Instant completedAt) {

    public record Doctor(UUID id, String name) {
        static Doctor of(Staff staff) {
            return staff == null ? null : new Doctor(staff.getId(), staff.getDisplayName());
        }
    }

    public static VisitResponse from(OpdVisit v, Integer ageYears) {
        return new VisitResponse(
                v.getId(), v.getEncounter().getId(), v.getTokenNumber(), v.getVisitDate(),
                v.getDepartment().getCode(), v.getDepartment().getName(), Doctor.of(v.getDoctor()),
                v.getPatient().getUhid().value(), v.getPatient().getFullName(), v.getPatient().getGender(), ageYears,
                v.getVisitType(), v.getPriority(), v.getQueueStatus(),
                v.getRegisteredAt(), v.getCalledAt(), v.getConsultationStartedAt(), v.getCompletedAt());
    }
}
