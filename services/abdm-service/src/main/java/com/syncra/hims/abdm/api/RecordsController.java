package com.syncra.hims.abdm.api;

import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import com.syncra.hims.abdm.carecontext.CareContextService;
import com.syncra.hims.abdm.consent.ConsentRepository;
import com.syncra.hims.abdm.dataflow.HealthInformationRepository;
import com.syncra.hims.abdm.fhir.EncounterRecord;
import com.syncra.hims.abdm.fhir.HealthRecords;
import com.syncra.hims.abdm.fhir.HiType;
import com.syncra.hims.abdm.security.Roles;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.audit.AuditService.Action;
import com.syncra.hims.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Milestone 2 for staff: an encounter's shareable records, its care context, and what was
 * consented and shared.
 *
 * <pre>
 * GET  /api/v1/abdm/encounters/{id}/records             record types available + care context
 * GET  /api/v1/abdm/encounters/{id}/records/{hiType}    the FHIR R4 document (application/fhir+json)
 * POST /api/v1/abdm/encounters/{id}/care-context        create the care context and link it to the ABHA
 * GET  /api/v1/abdm/patients/{id}/care-contexts         the patient's care contexts and link status
 * GET  /api/v1/abdm/patients/{id}/consents              consents received and transfers made under them
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/abdm")
public class RecordsController {

    public static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

    public record EncounterRecords(UUID encounterId, UUID patientId, Set<HiType> available, CareContext careContext) {
    }

    public record ConsentView(ConsentRepository.Consent consent, List<TransferView> transfers) {
    }

    public record TransferView(UUID transactionId, String status, String rejectionReason, Instant requestedAt,
                               List<HealthInformationRepository.Transfer> records) {
    }

    private final HealthRecords records;
    private final CareContextService careContexts;
    private final ConsentRepository consents;
    private final HealthInformationRepository transfers;
    private final AuditService audit;

    public RecordsController(HealthRecords records, CareContextService careContexts, ConsentRepository consents,
                             HealthInformationRepository transfers, AuditService audit) {
        this.records = records;
        this.careContexts = careContexts;
        this.consents = consents;
        this.transfers = transfers;
        this.audit = audit;
    }

    @GetMapping("/encounters/{encounterId}/records")
    @PreAuthorize(Roles.RECORD_LINKING)
    public EncounterRecords available(@PathVariable UUID encounterId) {
        EncounterRecord record = records.record(encounterId)
                .orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
        CareContext context = careContexts.forPatient(record.patient().id()).stream()
                .filter(c -> c.encounterId().equals(encounterId)).findFirst().orElse(null);
        return new EncounterRecords(encounterId, record.patient().id(), records.available(record), context);
    }

    @GetMapping("/encounters/{encounterId}/records/{hiType}")
    @PreAuthorize(Roles.RECORD_EXPORT)
    public ResponseEntity<String> document(@PathVariable UUID encounterId, @PathVariable String hiType) {
        HiType type = HiType.fromCode(hiType).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "UNKNOWN_HI_TYPE", "Record type must be one of " + java.util.Arrays.stream(HiType.values())
                        .map(HiType::code).collect(java.util.stream.Collectors.joining(", "))));
        EncounterRecord record = records.record(encounterId)
                .orElseThrow(() -> ApiException.notFound("Encounter", encounterId));
        HealthRecords.Document document = records.document(record, type)
                .orElseThrow(() -> ApiException.unprocessable("NO_SUCH_RECORD",
                        "This encounter has no " + type.code() + " record"));
        audit.record(Action.EXPORT, "fhir_document", encounterId, record.patient().id(),
                Map.of("hiType", type.code(), "sha256", document.sha256()));
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type.code() + "-"
                        + record.patient().uhid() + ".json\"")
                .body(document.json());
    }

    @PostMapping("/encounters/{encounterId}/care-context")
    @PreAuthorize(Roles.RECORD_LINKING)
    public ResponseEntity<CareContext> link(@PathVariable UUID encounterId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(careContexts.link(encounterId));
    }

    @GetMapping("/patients/{patientId}/care-contexts")
    @PreAuthorize(Roles.RECORD_LINKING)
    public List<CareContext> careContexts(@PathVariable UUID patientId) {
        return careContexts.forPatient(patientId);
    }

    @GetMapping("/patients/{patientId}/consents")
    @PreAuthorize(Roles.CONSENT_VIEW)
    public List<ConsentView> consents(@PathVariable UUID patientId) {
        return consents.forPatient(patientId).stream()
                .map(c -> new ConsentView(c, transfers.forConsent(c.id()).stream()
                        .map(r -> new TransferView(r.transactionId(), r.status(), r.rejectionReason(), r.requestedAt(),
                                transfers.transfers(r.id())))
                        .toList()))
                .toList();
    }
}
