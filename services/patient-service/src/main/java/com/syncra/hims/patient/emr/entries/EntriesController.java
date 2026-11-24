package com.syncra.hims.patient.emr.entries;

import com.syncra.hims.patient.emr.entries.EntriesRepository.Allergy;
import com.syncra.hims.patient.emr.entries.EntriesRepository.Diagnosis;
import com.syncra.hims.patient.emr.entries.EntriesRepository.Note;
import com.syncra.hims.patient.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET  /api/v1/emr/me                                                any staff: the caller's staff record
 * POST /api/v1/emr/encounters/{id}/diagnoses                          doctor
 * POST /api/v1/emr/encounters/{id}/diagnoses/{dxId}/entered-in-error  doctor
 * GET  /api/v1/emr/patients/{uhid}/allergies                          clinical staff
 * POST /api/v1/emr/patients/{uhid}/allergies                          clinical staff
 * PUT  /api/v1/emr/patients/{uhid}/allergies/{id}/status              clinical staff
 * POST /api/v1/emr/encounters/{id}/notes                              clinical staff (doctor-only types enforced)
 * PUT  /api/v1/emr/encounters/{id}/notes/{noteId}                     author, draft only (If-Match: version)
 * POST /api/v1/emr/encounters/{id}/notes/{noteId}/sign                author
 * POST /api/v1/emr/encounters/{id}/notes/{noteId}/entered-in-error    author
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emr")
public class EntriesController {

    public record DiagnosisRequest(
            @NotBlank @Size(max = 500) String description,
            @Pattern(regexp = "^[A-Za-z][0-9]{2}(\\.[0-9A-Za-z]{1,4})?$", message = "must be an ICD-10 code such as J06.9")
            String icd10Code,
            @Pattern(regexp = "^[0-9]{6,18}$", message = "must be a SNOMED CT concept id") String snomedCode,
            @Pattern(regexp = "^(PROVISIONAL|DIFFERENTIAL|FINAL)$") String type,
            boolean primary) {
    }

    public record AllergyRequest(
            @NotNull @Pattern(regexp = "^(DRUG|DRUG_CLASS|FOOD|ENVIRONMENT|OTHER)$") String allergenType,
            @NotBlank @Size(max = 200) String allergenText,
            @Size(max = 100) String drugClass,
            @Size(max = 300) String reaction,
            @Pattern(regexp = "^(MILD|MODERATE|SEVERE|LIFE_THREATENING)$") String severity) {
    }

    public record AllergyStatusRequest(@NotNull @Pattern(regexp = "^(ACTIVE|INACTIVE|RESOLVED|REFUTED)$") String status) {
    }

    public record NoteRequest(
            @NotBlank @Pattern(regexp = "^[A-Z_]{4,20}$") String noteType,
            @NotBlank @Size(max = 100_000) String content,
            boolean sign,
            UUID amendsNoteId) {
    }

    public record DraftRequest(@NotBlank @Size(max = 100_000) String content) {
    }

    private final EntriesService entries;

    public EntriesController(EntriesService entries) {
        this.entries = entries;
    }

    @GetMapping("/me")
    @PreAuthorize(Roles.ANY_STAFF)
    public EntriesService.Me me() {
        return entries.me();
    }

    @PostMapping("/encounters/{encounterId}/diagnoses")
    @PreAuthorize(Roles.CONSULTATION)
    @ResponseStatus(HttpStatus.CREATED)
    public Diagnosis addDiagnosis(@PathVariable UUID encounterId, @Valid @RequestBody DiagnosisRequest r) {
        return entries.addDiagnosis(encounterId,
                new EntriesService.DiagnosisInput(r.description(), r.icd10Code(), r.snomedCode(), r.type(), r.primary()));
    }

    @PostMapping("/encounters/{encounterId}/diagnoses/{diagnosisId}/entered-in-error")
    @PreAuthorize(Roles.CONSULTATION)
    public Diagnosis diagnosisInError(@PathVariable UUID encounterId, @PathVariable UUID diagnosisId) {
        return entries.diagnosisInError(encounterId, diagnosisId);
    }

    @GetMapping("/patients/{uhid}/allergies")
    @PreAuthorize(Roles.CLINICAL)
    public List<Allergy> allergies(@PathVariable String uhid) {
        return entries.allergies(uhid);
    }

    @PostMapping("/patients/{uhid}/allergies")
    @PreAuthorize(Roles.CLINICAL)
    @ResponseStatus(HttpStatus.CREATED)
    public Allergy addAllergy(@PathVariable String uhid, @Valid @RequestBody AllergyRequest r) {
        return entries.addAllergy(uhid, new EntriesService.AllergyInput(r.allergenType(), r.allergenText(),
                r.drugClass(), r.reaction(), r.severity()));
    }

    @PutMapping("/patients/{uhid}/allergies/{allergyId}/status")
    @PreAuthorize(Roles.CLINICAL)
    public Allergy allergyStatus(@PathVariable String uhid, @PathVariable UUID allergyId,
                                 @Valid @RequestBody AllergyStatusRequest r) {
        return entries.changeAllergyStatus(uhid, allergyId, r.status());
    }

    @PostMapping("/encounters/{encounterId}/notes")
    @PreAuthorize(Roles.CLINICAL)
    @ResponseStatus(HttpStatus.CREATED)
    public Note addNote(@PathVariable UUID encounterId, @Valid @RequestBody NoteRequest r) {
        return entries.addNote(encounterId, new EntriesService.NoteInput(r.noteType(), r.content(), r.sign(),
                r.amendsNoteId()));
    }

    @PutMapping("/encounters/{encounterId}/notes/{noteId}")
    @PreAuthorize(Roles.CLINICAL)
    public Note editDraft(@PathVariable UUID encounterId, @PathVariable UUID noteId,
                          @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody DraftRequest r) {
        long version;
        try {
            version = Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
        } catch (NumberFormatException e) {
            throw new com.syncra.hims.common.web.ApiException(HttpStatus.BAD_REQUEST, "BAD_IF_MATCH",
                    "If-Match must carry the note's version");
        }
        return entries.editDraft(encounterId, noteId, r.content(), version);
    }

    @PostMapping("/encounters/{encounterId}/notes/{noteId}/sign")
    @PreAuthorize(Roles.CLINICAL)
    public Note sign(@PathVariable UUID encounterId, @PathVariable UUID noteId) {
        return entries.sign(encounterId, noteId);
    }

    @PostMapping("/encounters/{encounterId}/notes/{noteId}/entered-in-error")
    @PreAuthorize(Roles.CLINICAL)
    public Note noteInError(@PathVariable UUID encounterId, @PathVariable UUID noteId) {
        return entries.noteInError(encounterId, noteId);
    }
}
