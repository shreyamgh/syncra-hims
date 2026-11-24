package com.syncra.hims.patient.emr;

import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.emr.dto.ClinicalCodes.Source;
import com.syncra.hims.patient.emr.dto.ConsultationRequest;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.ComplaintInput;
import com.syncra.hims.patient.emr.dto.ConsultationRequest.PrescriptionItemInput;
import com.syncra.hims.patient.emr.dto.EncounterRecord;
import com.syncra.hims.patient.emr.dto.VitalsInput;
import com.syncra.hims.patient.opd.OpdService;
import com.syncra.hims.patient.reference.ReferenceData;
import com.syncra.hims.patient.reference.Staff;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Doctor Desk's clinical record: triage vitals, and the signed consultation
 * (vitals, complaints, issued prescription, dictation provenance) saved in one
 * transaction — optionally completing the OPD visit at the same time.
 */
@Service
public class EmrService {

    private static final Set<String> CLOSED = Set.of("FINISHED", "CANCELLED");
    private static final int VITALS_HISTORY = 5;

    private final EmrRepository repository;
    private final ReferenceData reference;
    private final OpdService opd;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final HospitalTime time;

    private final com.syncra.hims.patient.emr.immunization.ImmunizationService immunizations;
    private final com.syncra.hims.patient.emr.entries.EntriesService entries;
    private final com.syncra.hims.patient.emr.orders.OrderRepository orders;
    private final com.syncra.hims.patient.emr.critical.CriticalResultRepository critical;
    private final com.syncra.hims.patient.emr.cdss.CdssGuard cdss;

    public EmrService(EmrRepository repository, ReferenceData reference, OpdService opd, CurrentUser currentUser,
                      AuditService audit, HospitalTime time,
                      com.syncra.hims.patient.emr.immunization.ImmunizationService immunizations,
                      com.syncra.hims.patient.emr.entries.EntriesService entries,
                      com.syncra.hims.patient.emr.orders.OrderRepository orders,
                      com.syncra.hims.patient.emr.cdss.CdssGuard cdss,
                      com.syncra.hims.patient.emr.critical.CriticalResultRepository critical) {
        this.critical = critical;
        this.immunizations = immunizations;
        this.entries = entries;
        this.orders = orders;
        this.cdss = cdss;
        this.repository = repository;
        this.reference = reference;
        this.opd = opd;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public EncounterRecord record(UUID encounterId) {
        return render(requireEncounter(encounterId));
    }

    /** Triage vitals (nurse or doctor). Dictated vitals are saved with the consultation instead. */
    @Transactional
    public EncounterRecord recordVitals(UUID encounterId, VitalsInput vitals) {
        EmrRepository.Header encounter = requireOpen(encounterId);
        if (!vitals.hasAnyValue()) {
            throw ApiException.unprocessable("EMPTY_VITALS", "Enter at least one vital sign");
        }
        if (vitals.sourceOrManual() == Source.DICTATION) {
            throw ApiException.unprocessable("DICTATION_NEEDS_SESSION",
                    "Dictated vitals are saved with the consultation, together with their dictation session");
        }
        repository.insertVitals(encounterId, currentUser.subject(), vitals, null);
        audit.record(Action.CREATE, "vital_sign", encounterId, encounter.patientId(),
                Map.of("source", vitals.sourceOrManual().name()));
        return render(encounter);
    }

    @Transactional
    public EncounterRecord recordConsultation(UUID encounterId, ConsultationRequest request) {
        EmrRepository.Header encounter = requireOpen(encounterId);
        Staff doctor = reference.currentStaff().filter(Staff::isDoctor)
                .orElseThrow(() -> ApiException.unprocessable("NO_DOCTOR_RECORD",
                        "Your login is not linked to a doctor's staff record, so you cannot sign prescriptions"));
        String signer = currentUser.subject();

        Map<String, UUID> sessions = new HashMap<>();
        for (ConsultationRequest.DictationSession session : request.sessions()) {
            try {
                sessions.put(session.aiSessionId(), repository.upsertDictationSession(encounterId, doctor.getId(),
                        session));
            } catch (IllegalStateException e) {
                throw ApiException.conflict("DICTATION_SESSION_REUSED",
                        "Dictation session " + session.aiSessionId() + " belongs to another encounter");
            }
        }

        boolean vitalsSaved = false;
        if (request.vitals() != null && request.vitals().hasAnyValue()) {
            VitalsInput v = request.vitals();
            repository.insertVitals(encounterId, signer, v, provenance(v.sourceOrManual(), v.dictationSessionRef(),
                    sessions));
            vitalsSaved = true;
        }

        for (ComplaintInput c : request.complaintList()) {
            Source source = textSource(c.source());
            repository.insertComplaint(encounterId, signer, c, source.name(),
                    provenance(source, c.dictationSessionRef(), sessions));
        }

        com.syncra.hims.patient.emr.entries.EntriesRepository.EncounterFacts facts =
                new com.syncra.hims.patient.emr.entries.EntriesRepository.EncounterFacts(encounterId,
                        encounter.patientId(), encounter.status(), null);
        for (ConsultationRequest.DiagnosisInput d : request.diagnosisList()) {
            Source source = textSource(d.source());
            entries.recordDiagnosis(facts, new com.syncra.hims.patient.emr.entries.EntriesService.DiagnosisInput(
                            d.description(), d.icd10Code(), d.snomedCode(), d.type(), d.primary()),
                    source.name(), provenance(source, d.dictationSessionRef(), sessions));
        }

        int prescribed = 0;
        com.syncra.hims.patient.emr.cdss.CdssGuard.Outcome safety = null;
        if (request.prescription() != null) {
            // Drug-safety check first: serious alerts without a reason stop the whole signature.
            safety = cdss.check(encounter.patientId(), encounter.gender(), encounter.dateOfBirth(),
                    request.vitals() == null ? null : request.vitals().weightKg(), request.prescription().items(),
                    request.cdssOverrides());
            UUID prescriptionId = repository.insertDraftPrescription(encounterId, encounter.patientId(),
                    doctor.getId(), blankToNull(request.prescription().advice()),
                    request.prescription().followUpDate());
            List<PrescriptionItemInput> items = request.prescription().items();
            for (int line = 0; line < items.size(); line++) {
                PrescriptionItemInput item = items.get(line);
                Source source = textSource(item.source());
                repository.insertPrescriptionItem(prescriptionId, line + 1, item, source.name(),
                        provenance(source, item.dictationSessionRef(), sessions));
            }
            repository.issuePrescription(prescriptionId, time.now());
            cdss.record(encounterId, prescriptionId, safety, signer);
            prescribed = items.size();
        }

        if (request.completeVisit() && encounter.visitId() != null) {
            opd.completeFromConsultation(encounter.visitId(), doctor);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("vitals", vitalsSaved);
        details.put("complaints", request.complaintList().size());
        details.put("prescriptionItems", prescribed);
        details.put("dictationSessions", sessions.size());
        details.put("diagnoses", request.diagnosisList().size());
        if (safety != null) {
            details.put("cdssChecked", safety.checked());
            details.put("cdssAlerts", safety.alerts().size());
            details.put("cdssOverridden", safety.alerts().stream().filter(a -> a.isSerious()).count());
        }
        details.put("completedVisit", request.completeVisit() && encounter.visitId() != null);
        audit.record(Action.CREATE, "consultation", encounterId, encounter.patientId(), details);
        return render(repository.header(encounterId).orElseThrow());
    }

    private EncounterRecord render(EmrRepository.Header h) {
        List<EncounterRecord.Vitals> vitals = repository.vitals(h.encounterId(), VITALS_HISTORY);
        return new EncounterRecord(
                h.encounterId(), h.encounterType(), h.status(),
                new EncounterRecord.Patient(h.patientId(), h.uhid(), h.patientName(), h.gender(),
                        time.ageInYears(h.dateOfBirth())),
                h.departmentCode(), h.departmentName(),
                h.visitId() == null ? null : new EncounterRecord.Visit(h.visitId(), h.tokenNumber(), h.queueStatus()),
                vitals.isEmpty() ? null : vitals.get(0), vitals,
                repository.complaints(h.encounterId()), repository.prescriptions(h.encounterId()),
                repository.activeAllergies(h.patientId()), immunizations.forEncounter(h.encounterId()),
                entries.diagnoses(h.encounterId()), entries.notes(h.encounterId()), orders.forEncounter(h.encounterId()),
                repository.cdssAlerts(h.encounterId()),
                orders.latestCreatinine(h.patientId(), time.now().minus(java.time.Duration.ofDays(90))).orElse(null),
                critical.list(null, null, null, h.encounterId()));
    }

    private EmrRepository.Header requireEncounter(UUID encounterId) {
        return repository.header(encounterId).orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
    }

    private EmrRepository.Header requireOpen(UUID encounterId) {
        EmrRepository.Header encounter = requireEncounter(encounterId);
        if (CLOSED.contains(encounter.status())) {
            throw ApiException.conflict("ENCOUNTER_CLOSED", "This encounter is " + encounter.status().toLowerCase()
                    + "; the record can no longer be changed");
        }
        return encounter;
    }

    /** Resolves a DICTATION row to the session it came from; MANUAL rows carry no session. */
    private static UUID provenance(Source source, String sessionRef, Map<String, UUID> sessions) {
        if (source != Source.DICTATION) {
            return null;
        }
        UUID id = sessionRef == null ? null : sessions.get(sessionRef);
        if (id == null) {
            throw ApiException.unprocessable("DICTATION_SESSION_UNKNOWN",
                    "A dictated entry refers to a dictation session that is not part of this consultation");
        }
        return id;
    }

    private static Source textSource(Source source) {
        if (source == Source.DEVICE) {
            throw ApiException.unprocessable("INVALID_SOURCE", "Only vital signs can come from a device");
        }
        return source == null ? Source.MANUAL : source;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
