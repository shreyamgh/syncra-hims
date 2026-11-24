package com.syncra.hims.patient.emr.entries;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.entries.EntriesRepository.Allergy;
import com.syncra.hims.patient.emr.entries.EntriesRepository.Diagnosis;
import com.syncra.hims.patient.emr.entries.EntriesRepository.EncounterFacts;
import com.syncra.hims.patient.emr.entries.EntriesRepository.Note;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diagnoses, allergies and clinical notes. Nothing clinical is deleted or rewritten once it
 * counts: a wrong diagnosis or note is marked entered in error, a signed note is corrected by
 * an amendment, and an allergy is ended by changing its status.
 */
@Service
public class EntriesService {

    /** Note types only a doctor writes. Radiology reports come through the imaging worklist. */
    static final Set<String> DOCTOR_NOTES = Set.of("HISTORY", "EXAMINATION", "PROCEDURE", "ADVICE", "DISCHARGE_SUMMARY");
    static final Set<String> NOTE_TYPES = Set.of("HISTORY", "EXAMINATION", "PROGRESS", "PROCEDURE", "NURSING", "ADVICE",
            "DISCHARGE_SUMMARY", "OTHER");

    private final EntriesRepository repository;
    private final ReferenceData reference;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public EntriesService(EntriesRepository repository, ReferenceData reference, CurrentUser currentUser,
                          AuditService audit) {
        this.repository = repository;
        this.reference = reference;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ diagnoses

    public record DiagnosisInput(String description, String icd10Code, String snomedCode, String type, boolean primary) {
    }

    @Transactional
    public Diagnosis addDiagnosis(UUID encounterId, DiagnosisInput input) {
        EncounterFacts encounter = requireNotCancelled(encounterId);
        UUID id = recordDiagnosis(encounter, input, "MANUAL", null);
        return repository.diagnosis(id).orElseThrow();
    }

    /** Also used when a consultation is signed (dictated diagnoses carry their session). */
    public UUID recordDiagnosis(EncounterFacts encounter, DiagnosisInput input, String source, UUID dictationSessionId) {
        UUID id = repository.insertDiagnosis(encounter.encounterId(), input.description().trim(),
                blankToNull(input.icd10Code()) == null ? null : input.icd10Code().trim().toUpperCase(),
                blankToNull(input.snomedCode()), input.type() == null ? "PROVISIONAL" : input.type(), input.primary(),
                source, dictationSessionId, currentUser.subject());
        audit.record(Action.CREATE, "diagnosis", id, encounter.patientId(),
                Map.of("type", input.type() == null ? "PROVISIONAL" : input.type(), "coded", input.icd10Code() != null));
        return id;
    }

    @Transactional
    public Diagnosis diagnosisInError(UUID encounterId, UUID diagnosisId) {
        EncounterFacts encounter = requireEncounter(encounterId);
        Diagnosis diagnosis = repository.diagnosis(diagnosisId).filter(d -> d.encounterId().equals(encounterId))
                .orElseThrow(() -> ApiException.notFound("Diagnosis", diagnosisId));
        if (repository.diagnosisInError(diagnosis.id()) == 0) {
            throw ApiException.conflict("ALREADY_IN_ERROR", "This diagnosis is already marked entered in error");
        }
        audit.record(Action.UPDATE, "diagnosis", diagnosisId, encounter.patientId(), Map.of("action", "entered_in_error"));
        return repository.diagnosis(diagnosisId).orElseThrow();
    }

    public List<Diagnosis> diagnoses(UUID encounterId) {
        return repository.diagnoses(encounterId);
    }

    // ------------------------------------------------------------------ allergies

    public record AllergyInput(String allergenType, String allergenText, String drugClass, String reaction,
                               String severity) {
    }

    @Transactional
    public Allergy addAllergy(String uhid, AllergyInput input) {
        UUID patientId = requirePatient(uhid);
        if ("DRUG_CLASS".equals(input.allergenType()) && blankToNull(input.drugClass()) == null) {
            throw ApiException.unprocessable("DRUG_CLASS_REQUIRED", "Name the drug class (e.g. Penicillins)");
        }
        if (repository.activeAllergyExists(patientId, input.allergenText().trim())) {
            throw ApiException.conflict("ALLERGY_EXISTS", "This allergy is already recorded as active");
        }
        UUID id = repository.insertAllergy(patientId, input.allergenType(), input.allergenText().trim(),
                blankToNull(input.drugClass()), blankToNull(input.reaction()), input.severity(), currentUser.subject());
        audit.record(Action.CREATE, "allergy", id, patientId, Map.of("type", input.allergenType()));
        return repository.allergy(patientId, id).orElseThrow();
    }

    @Transactional
    public Allergy changeAllergyStatus(String uhid, UUID allergyId, String status) {
        UUID patientId = requirePatient(uhid);
        Allergy allergy = repository.allergy(patientId, allergyId)
                .orElseThrow(() -> ApiException.notFound("Allergy", allergyId));
        if (allergy.status().equals(status)) {
            return allergy;
        }
        repository.allergyStatus(allergyId, status);
        audit.record(Action.UPDATE, "allergy", allergyId, patientId, Map.of("status", status));
        return repository.allergy(patientId, allergyId).orElseThrow();
    }

    public List<Allergy> allergies(String uhid) {
        return repository.allergies(requirePatient(uhid));
    }

    // ------------------------------------------------------------------ notes

    public record NoteInput(String noteType, String content, boolean sign, UUID amendsNoteId) {
    }

    @Transactional
    public Note addNote(UUID encounterId, NoteInput input) {
        EncounterFacts encounter = requireNotCancelled(encounterId);
        if (!NOTE_TYPES.contains(input.noteType())) {
            throw ApiException.unprocessable("NOTE_TYPE", "RADIOLOGY_REPORT notes are written from the imaging worklist");
        }
        Staff author = requireStaff();
        if (DOCTOR_NOTES.contains(input.noteType()) && !author.isDoctor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DOCTOR_NOTE", "Only a doctor writes a " + label(input.noteType()));
        }
        if (input.amendsNoteId() != null) {
            Note original = repository.lockNote(input.amendsNoteId())
                    .filter(n -> n.encounterId().equals(encounterId))
                    .orElseThrow(() -> ApiException.notFound("Note", input.amendsNoteId()));
            if (original.signedAt() == null) {
                throw ApiException.conflict("NOTE_NOT_SIGNED", "Edit the draft instead of amending it");
            }
            if (!original.noteType().equals(input.noteType())) {
                throw ApiException.unprocessable("NOTE_TYPE", "An amendment has the same type as the note it amends");
            }
        }
        UUID id = repository.insertNote(encounterId, input.noteType(), input.content(), author.getId(),
                input.sign() || input.amendsNoteId() != null, input.amendsNoteId());
        audit.record(Action.CREATE, "clinical_note", id, encounter.patientId(),
                Map.of("type", input.noteType(), "signed", input.sign(), "amendment", input.amendsNoteId() != null));
        return repository.lockNote(id).orElseThrow();
    }

    @Transactional
    public Note editDraft(UUID encounterId, UUID noteId, String content, long version) {
        Note note = requireOwnNote(encounterId, noteId);
        if (note.signedAt() != null) {
            throw ApiException.conflict("NOTE_SIGNED", "A signed note cannot be edited; add an amendment");
        }
        if (repository.updateDraft(noteId, content, version) == 0) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE_NOTE", "The draft changed meanwhile; reload it");
        }
        return repository.lockNote(noteId).orElseThrow();
    }

    @Transactional
    public Note sign(UUID encounterId, UUID noteId) {
        Note note = requireOwnNote(encounterId, noteId);
        if (note.signedAt() != null) {
            throw ApiException.conflict("NOTE_SIGNED", "This note is already signed");
        }
        repository.sign(noteId);
        UUID patientId = requireEncounter(encounterId).patientId();
        audit.record(Action.UPDATE, "clinical_note", noteId, patientId, Map.of("action", "signed"));
        return repository.lockNote(noteId).orElseThrow();
    }

    @Transactional
    public Note noteInError(UUID encounterId, UUID noteId) {
        Note note = requireOwnNote(encounterId, noteId);
        if (repository.noteInError(note.id()) == 0) {
            throw ApiException.conflict("ALREADY_IN_ERROR", "This note is already marked entered in error");
        }
        audit.record(Action.UPDATE, "clinical_note", noteId, requireEncounter(encounterId).patientId(),
                Map.of("action", "entered_in_error"));
        return repository.lockNote(noteId).orElseThrow();
    }

    public List<Note> notes(UUID encounterId) {
        return repository.notes(encounterId);
    }

    // ------------------------------------------------------------------ caller

    /** The caller's staff record, which notes, orders and signatures are attributed to. */
    public record Me(boolean linked, UUID staffId, String name, String staffRole, boolean doctor) {
    }

    public Me me() {
        return reference.currentStaff()
                .map(s -> new Me(true, s.getId(), s.getDisplayName(), s.getStaffRole().name(), s.isDoctor()))
                .orElseGet(() -> new Me(false, null, null, null, false));
    }

    // ------------------------------------------------------------------ helpers

    EncounterFacts requireEncounter(UUID encounterId) {
        return repository.encounter(encounterId).orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
    }

    private EncounterFacts requireNotCancelled(UUID encounterId) {
        EncounterFacts encounter = requireEncounter(encounterId);
        if ("CANCELLED".equals(encounter.status())) {
            throw ApiException.conflict("ENCOUNTER_CANCELLED", "This encounter was cancelled");
        }
        return encounter;
    }

    private UUID requirePatient(String uhid) {
        return repository.patientByUhid(uhid.trim().toUpperCase()).orElseThrow(() -> ApiException.notFound("Patient", uhid));
    }

    private Staff requireStaff() {
        return reference.currentStaff().orElseThrow(() -> ApiException.unprocessable("STAFF_RECORD_REQUIRED",
                "Your login is not linked to a staff record; ask the administrator to link it"));
    }

    private Note requireOwnNote(UUID encounterId, UUID noteId) {
        Note note = repository.lockNote(noteId).filter(n -> n.encounterId().equals(encounterId))
                .orElseThrow(() -> ApiException.notFound("Note", noteId));
        if (!note.authorId().equals(requireStaff().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_NOTE", "Only the author can change this note");
        }
        return note;
    }

    private static String label(String type) {
        return type.replace('_', ' ').toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
