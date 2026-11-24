package com.syncra.hims.patient.ipd.dto;

import com.syncra.hims.patient.ipd.BedCategory;
import com.syncra.hims.patient.ipd.BedStatus;
import com.syncra.hims.patient.registration.Gender;
import java.time.Instant;
import java.util.UUID;

/** Read models for the bed board, ward census and bed finder. */
public final class BedViews {

    private BedViews() {
    }

    public record BoardEntry(String wardCode, String wardName, String wardType, String bedCode,
                             BedCategory bedCategory, BedStatus bedStatus, Occupant occupant) {
    }

    public record Occupant(UUID admissionId, String admissionNumber, String uhid, String patientName,
                           Gender gender, Integer ageYears, Instant admittedAt, String admittingDoctor) {
    }

    public record WardCensus(String wardCode, String wardName, String wardType, String genderRestriction,
                             int total, int available, int occupied, int reserved, int cleaning,
                             int outOfService) {
    }

    public record AvailableBed(String bedCode, String wardCode, String wardName, BedCategory bedCategory,
                               boolean hasOxygen, boolean hasMonitor, boolean hasVentilator) {
    }

    public record BedState(String bedCode, BedStatus status) {
    }
}
