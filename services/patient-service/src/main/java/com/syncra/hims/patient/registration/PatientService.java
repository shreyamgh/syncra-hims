package com.syncra.hims.patient.registration;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.common.web.ETags;
import com.syncra.hims.patient.registration.dto.PatientSummary;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, demographic updates and look-up of patients. */
@Service
public class PatientService {

    private static final int MAX_PAGE_SIZE = 50;

    private final PatientRepository patients;
    private final PatientSearch search;
    private final AuditService audit;
    private final HospitalTime time;

    public PatientService(PatientRepository patients, PatientSearch search, AuditService audit, HospitalTime time) {
        this.patients = patients;
        this.search = search;
        this.audit = audit;
        this.time = time;
    }

    /**
     * Registers a patient; the database issues the UHID. Unless the desk confirms
     * otherwise, probable duplicates are returned as a 409 with candidate UHIDs so
     * the patient's existing record is reused instead of fragmenting their history.
     */
    @Transactional
    public Patient register(Demographics details, RegistrationSource source, boolean confirmedNotDuplicate) {
        if (!confirmedNotDuplicate) {
            List<PatientSummary> candidates = search.potentialDuplicates(details).stream()
                    .map(this::summarise)
                    .toList();
            if (!candidates.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "POSSIBLE_DUPLICATE",
                        "A similar patient is already registered. Use the existing UHID, or resubmit with "
                                + "confirmNotDuplicate=true if this is a different person.",
                        Map.of("candidates", candidates));
            }
        }
        Patient patient = patients.saveAndFlush(Patient.register(details, source));
        audit.record(Action.CREATE, "patient", patient.getUhid(), patient.getId(),
                Map.of("source", source.name()));
        return patient;
    }

    @Transactional(readOnly = true)
    public Patient get(Uhid uhid) {
        return patients.findByUhid(uhid.value()).orElseThrow(() -> ApiException.notFound("Patient", uhid));
    }

    /** A patient who can receive care now: not merged, active and alive. */
    @Transactional(readOnly = true)
    public Patient requireCareable(Uhid uhid) {
        Patient patient = get(uhid);
        if (patient.getMergedIntoId() != null || !patient.isActive()) {
            throw ApiException.conflict("PATIENT_INACTIVE",
                    "UHID " + uhid + " is no longer active (merged or deactivated)");
        }
        if (patient.isDeceased()) {
            throw ApiException.unprocessable("PATIENT_DECEASED", "UHID " + uhid + " is recorded as deceased");
        }
        return patient;
    }

    @Transactional
    public Patient update(Uhid uhid, long expectedVersion, Demographics details) {
        Patient patient = get(uhid);
        ETags.checkCurrent(expectedVersion, patient.getVersion());
        Set<String> changed = patient.demographics().changedFields(details);
        if (changed.isEmpty()) {
            return patient;
        }
        patient.updateDemographics(details);
        patients.saveAndFlush(patient);
        audit.record(Action.UPDATE, "patient", uhid, patient.getId(), Map.of("fields", changed));
        return patient;
    }

    /**
     * Free-text search: a UHID, a 10-digit mobile, a 14-digit ABHA number, an ABHA
     * address, or otherwise a (fuzzy) name.
     */
    @Transactional(readOnly = true)
    public List<PatientSummary> search(String rawQuery, int page, int size) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 3) {
            throw ApiException.unprocessable("QUERY_TOO_SHORT", "Enter at least 3 characters to search");
        }
        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;
        String digits = query.replaceAll("[\\s-]", "");

        List<PatientSearch.Row> rows;
        if (Uhid.looksLike(query)) {
            rows = search.byUhid(Uhid.parse(query).value());
        } else if (digits.matches("[6-9]\\d{9}")) {
            rows = search.byMobile(digits, limit, offset);
        } else if (digits.matches("\\d{14}")) {
            rows = search.byAbhaNumber(digits);
        } else if (query.contains("@")) {
            rows = search.byAbhaAddress(query);
        } else {
            rows = search.byName(query, limit, offset);
        }
        return rows.stream().map(this::summarise).toList();
    }

    public Integer ageOf(Patient patient) {
        return time.ageInYears(patient.getDateOfBirth());
    }

    private PatientSummary summarise(PatientSearch.Row row) {
        return PatientSummary.from(row, time.ageInYears(row.dateOfBirth()),
                row.registeredAt().atZone(time.zone()).toLocalDate());
    }
}
