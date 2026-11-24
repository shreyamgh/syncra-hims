package com.syncra.hims.abdm.hiu;

import com.syncra.hims.abdm.security.Roles;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records from other hospitals, for the treating doctor (ABDM HIU):
 *
 * <pre>
 * POST /api/v1/abdm/hiu/consent-requests                     ask the patient's consent (in their ABHA app)
 * GET  /api/v1/abdm/hiu/patients/{uhid}/consent-requests     requests, the HIPs that answered, transfers
 * GET  /api/v1/abdm/hiu/patients/{uhid}/records              records received (until their erase time)
 * GET  /api/v1/abdm/hiu/records/{id}                         one record, summarised for reading (audited)
 * GET  /api/v1/abdm/hiu/records/{id}/fhir                    the FHIR document as received (audited)
 * POST /api/v1/abdm/hiu/consents/{consentId}/data-requests   ask that HIP again (after a failed transfer)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/abdm/hiu")
public class HiuController {

    /**
     * @param from      first day of the records wanted
     * @param to        last day (today at the latest)
     * @param keepUntil last day the records may be kept here (they are erased after it)
     */
    public record ConsentRequestBody(@NotBlank String uhid, @NotBlank String purpose,
                                     @NotEmpty @Size(max = 8) List<@NotBlank String> hiTypes,
                                     @NotNull LocalDate from, @NotNull LocalDate to, @NotNull LocalDate keepUntil) {
    }

    private final HiuService hiu;
    private final CurrentUser currentUser;
    private final HospitalTime time;

    public HiuController(HiuService hiu, CurrentUser currentUser, HospitalTime time) {
        this.hiu = hiu;
        this.currentUser = currentUser;
        this.time = time;
    }

    @PostMapping("/consent-requests")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public HiuService.ConsentView requestConsent(@Valid @RequestBody ConsentRequestBody body) {
        Instant from = body.from().atStartOfDay(time.zone()).toInstant();
        Instant endOfTo = body.to().plusDays(1).atStartOfDay(time.zone()).toInstant().minusMillis(1);
        Instant to = endOfTo.isAfter(time.now()) ? time.now() : endOfTo;
        Instant eraseAt = body.keepUntil().plusDays(1).atStartOfDay(time.zone()).toInstant().minusMillis(1);
        HiuRepository.ConsentRequest created = hiu.requestConsent(currentUser.subject(), new HiuService.NewConsentRequest(
                body.uhid(), body.purpose(), body.hiTypes(), from, to, eraseAt));
        return hiu.consents(body.uhid()).stream().filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
    }

    @GetMapping("/patients/{uhid}/consent-requests")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    public List<HiuService.ConsentView> consents(@PathVariable String uhid) {
        return hiu.consents(uhid);
    }

    @GetMapping("/patients/{uhid}/records")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    public List<HiuService.RecordView> records(@PathVariable String uhid) {
        return hiu.records(uhid);
    }

    @GetMapping("/records/{id}")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    public ResponseEntity<HiuService.RecordDetail> record(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(hiu.record(id));
    }

    /** The content type is preset, so a browser asking for application/json still gets the document. */
    @GetMapping("/records/{id}/fhir")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    public ResponseEntity<String> fhir(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/fhir+json")).body(hiu.bundle(id));
    }

    @PostMapping("/consents/{consentId}/data-requests")
    @PreAuthorize(Roles.EXTERNAL_RECORDS)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public HiuRepository.DataRequest requestAgain(@PathVariable UUID consentId, @RequestParam String uhid) {
        return hiu.requestDataAgain(consentId, hiu.patientId(uhid));
    }
}
