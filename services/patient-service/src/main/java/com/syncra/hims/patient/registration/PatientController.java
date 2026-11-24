package com.syncra.hims.patient.registration;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.patient.security.Roles;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.common.web.ETags;
import com.syncra.hims.patient.registration.dto.PatientRequest;
import com.syncra.hims.patient.registration.dto.PatientResponse;
import com.syncra.hims.patient.registration.dto.PatientSummary;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Patient registration and look-up: {@code /api/v1/patients}. */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patients;
    private final AuditService audit;
    private final HospitalTime time;

    public PatientController(PatientService patients, AuditService audit, HospitalTime time) {
        this.patients = patients;
        this.audit = audit;
        this.time = time;
    }

    /** Registers a patient and returns the new UHID (201, Location, ETag). */
    @PostMapping
    @PreAuthorize(Roles.REGISTRATION)
    public ResponseEntity<PatientResponse> register(
            @Valid @RequestBody PatientRequest request,
            @RequestParam(defaultValue = "false") boolean confirmNotDuplicate) {
        RegistrationSource source = request.sourceOrDefault();
        if (!request.hasAge() && source != RegistrationSource.EMERGENCY) {
            throw ApiException.unprocessable("AGE_REQUIRED",
                    "Give the date of birth or an approximate age (only emergency registrations may omit it)");
        }
        Patient patient = patients.register(toDemographics(request), source, confirmNotDuplicate);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patient.getUhid().value()))
                .eTag(ETags.of(patient.getVersion()))
                .body(PatientResponse.from(patient, patients.ageOf(patient)));
    }

    @GetMapping("/{uhid}")
    @PreAuthorize(Roles.ANY_STAFF)
    public ResponseEntity<PatientResponse> get(@PathVariable String uhid) {
        Patient patient = patients.get(Uhid.parse(uhid));
        audit.record(Action.READ, "patient", patient.getUhid(), patient.getId());
        return ResponseEntity.ok()
                .eTag(ETags.of(patient.getVersion()))
                .body(PatientResponse.from(patient, patients.ageOf(patient)));
    }

    /** Replaces demographics; requires the current ETag in {@code If-Match}. */
    @PutMapping("/{uhid}")
    @PreAuthorize(Roles.REGISTRATION)
    public ResponseEntity<PatientResponse> update(
            @PathVariable String uhid,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody PatientRequest request) {
        long expectedVersion = ETags.requireVersion(ifMatch);
        Patient patient = patients.update(Uhid.parse(uhid), expectedVersion, toDemographics(request));
        return ResponseEntity.ok()
                .eTag(ETags.of(patient.getVersion()))
                .body(PatientResponse.from(patient, patients.ageOf(patient)));
    }

    /** Search by UHID, mobile, ABHA number/address or name. Mobiles are masked in results. */
    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    public List<PatientSummary> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<PatientSummary> results = patients.search(query, page, size);
        // The query itself may be PHI (a name or mobile), so only the result count is audited.
        audit.record(Action.READ, "patient_search", null, null, Map.of("results", results.size()));
        return results;
    }

    private Demographics toDemographics(PatientRequest request) {
        try {
            return request.toDemographics(time.today());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("INVALID_VALUE", e.getMessage());
        }
    }
}
