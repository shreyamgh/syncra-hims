package com.syncra.hims.patient.emr.immunization;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.immunization.ImmunizationInput.Status;
import com.syncra.hims.patient.emr.immunization.ImmunizationRepository.EncounterFacts;
import com.syncra.hims.patient.emr.immunization.ImmunizationRepository.Immunization;
import com.syncra.hims.patient.emr.immunization.ImmunizationRepository.Vaccine;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vaccinations, recorded against the encounter in which they were given (or in which an earlier
 * dose was transcribed from the patient's card). Checks before saving:
 *
 * <ul>
 *   <li>the encounter is not cancelled, and at most {@value #LATE_HOURS} hours have passed since it ended;</li>
 *   <li>a dose given here has its batch and expiry, and was in date (an expired vial is refused);</li>
 *   <li>a dose given here falls within the visit; a transcribed one lies between birth and now;</li>
 *   <li>the dose number fits the vaccine's series, and that dose is not already on record.</li>
 * </ul>
 * Rows are never edited; a wrong one is marked entered in error.
 */
@Service
public class ImmunizationService {

    static final int LATE_HOURS = 24;
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final ImmunizationRepository repository;
    private final ReferenceData reference;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    public ImmunizationService(ImmunizationRepository repository, ReferenceData reference, CurrentUser currentUser,
                               AuditService audit, HospitalTime time) {
        this.repository = repository;
        this.reference = reference;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    public List<Vaccine> vaccines() {
        return repository.vaccines();
    }

    @Transactional
    public Immunization record(UUID encounterId, ImmunizationInput input) {
        EncounterFacts encounter = repository.encounter(encounterId)
                .orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
        Instant now = time.now();
        if ("CANCELLED".equals(encounter.status())) {
            throw ApiException.conflict("ENCOUNTER_CANCELLED", "This encounter was cancelled");
        }
        if (encounter.endedAt() != null && encounter.endedAt().isBefore(now.minus(Duration.ofHours(LATE_HOURS)))) {
            throw ApiException.conflict("ENCOUNTER_CLOSED",
                    "Vaccinations can be added up to " + LATE_HOURS + " hours after the visit ends");
        }
        Vaccine vaccine = repository.vaccine(input.vaccineCode().trim().toUpperCase())
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_VACCINE",
                        "Vaccine " + input.vaccineCode() + " is not in the catalogue"));

        Instant at = input.administeredAt() == null ? now : input.administeredAt();
        LocalDate day = time.dateOf(at);
        if (at.isAfter(now.plus(CLOCK_TOLERANCE))) {
            throw ApiException.unprocessable("FUTURE_DATE", "The vaccination time is in the future");
        }
        if (encounter.dateOfBirth() != null && day.isBefore(encounter.dateOfBirth())) {
            throw ApiException.unprocessable("BEFORE_BIRTH", "The vaccination date is before the patient's birth");
        }
        if (input.isPrimarySource() && at.isBefore(encounter.startedAt().minus(Duration.ofHours(1)))) {
            throw ApiException.unprocessable("NOT_IN_THIS_VISIT", "A dose given here must fall within this visit; "
                    + "record an earlier dose as transcribed from the patient's card");
        }
        if (input.doseNumber() != null && vaccine.seriesDoses() != null && input.doseNumber() > vaccine.seriesDoses()) {
            throw ApiException.unprocessable("DOSE_OUT_OF_SERIES",
                    vaccine.name() + " has " + vaccine.seriesDoses() + " dose(s) in its series");
        }
        boolean givenHere = input.isPrimarySource() && input.statusOrDefault() == Status.COMPLETED;
        if (givenHere && (input.lotNumber() == null || input.lotNumber().isBlank() || input.expiryDate() == null)) {
            throw ApiException.unprocessable("BATCH_REQUIRED", "Enter the batch (lot) number and expiry of the vial");
        }
        if (input.expiryDate() != null && input.expiryDate().isBefore(day)) {
            throw ApiException.unprocessable("VACCINE_EXPIRED",
                    "This vial expired on " + input.expiryDate() + "; it must not be administered");
        }
        if (input.nextDueDate() != null && !input.nextDueDate().isAfter(day)) {
            throw ApiException.unprocessable("NEXT_DUE_NOT_LATER", "The next due date must be after the vaccination");
        }
        UUID administeredBy = input.administeredBy() != null ? input.administeredBy()
                : givenHere ? reference.currentStaff().map(Staff::getId).orElse(null) : null;

        UUID id = repository.insert(encounter, input, vaccine, at, administeredBy, currentUser.subject());
        audit.record(Action.CREATE, "immunization", id, encounter.patientId(),
                Map.of("vaccine", vaccine.code(), "status", input.statusOrDefault().name(),
                        "transcribed", !input.isPrimarySource()));
        return repository.find(id).orElseThrow();
    }

    @Transactional
    public Immunization markEnteredInError(UUID encounterId, UUID immunizationId, String reason) {
        Immunization existing = repository.find(immunizationId)
                .filter(i -> i.encounterId().equals(encounterId))
                .orElseThrow(() -> ApiException.notFound("Immunization", immunizationId));
        if (repository.markEnteredInError(immunizationId, reason.trim()) == 0) {
            throw ApiException.conflict("ALREADY_IN_ERROR", "This vaccination is already marked entered in error");
        }
        UUID patientId = repository.encounter(encounterId).map(EncounterFacts::patientId).orElse(null);
        audit.record(Action.UPDATE, "immunization", existing.id(), patientId, Map.of("action", "entered_in_error"));
        return repository.find(immunizationId).orElseThrow();
    }

    public List<Immunization> forEncounter(UUID encounterId) {
        return repository.forEncounter(encounterId);
    }

    /** @return the patient id and their vaccination history, newest first */
    @Transactional(readOnly = true)
    public Map.Entry<UUID, List<Immunization>> history(String uhid) {
        UUID patientId = repository.patientByUhid(uhid.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Patient", uhid));
        return Map.entry(patientId, repository.forPatient(patientId));
    }
}
