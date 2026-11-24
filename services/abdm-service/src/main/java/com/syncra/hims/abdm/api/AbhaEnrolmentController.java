package com.syncra.hims.abdm.api;

import com.syncra.hims.abdm.abha.AbhaClient.OtpSent;
import com.syncra.hims.abdm.enrolment.AbhaEnrolmentClient.Card;
import com.syncra.hims.abdm.enrolment.AbhaEnrolmentService;
import com.syncra.hims.abdm.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Milestone 1 — creating an ABHA with Aadhaar at the registration desk.
 *
 * <pre>
 * POST /api/v1/abdm/abha/enrolments                          Aadhaar + patient consent: OTP to the Aadhaar-linked mobile
 * POST /api/v1/abdm/abha/enrolments/{id}/verify              OTP + communication mobile: ABHA created (optionally attached)
 * POST /api/v1/abdm/abha/enrolments/{id}/mobile/otp          verify a mobile that is not Aadhaar-linked ...
 * POST /api/v1/abdm/abha/enrolments/{id}/mobile/verify       ... with its OTP
 * GET  /api/v1/abdm/abha/enrolments/{id}/address-suggestions
 * POST /api/v1/abdm/abha/enrolments/{id}/address             create the ABHA address (e.g. ramesh.k1975)
 * GET  /api/v1/abdm/abha/enrolments/{id}/card                the ABHA card to print
 * </pre>
 * Responses carrying identity data are not cached. The enrolment belongs to whoever started it.
 */
@RestController
@RequestMapping("/api/v1/abdm/abha/enrolments")
public class AbhaEnrolmentController {

    /** @param patientConsent the patient heard ABDM's consent text and agreed */
    public record StartRequest(@NotBlank String aadhaarNumber, @NotNull Boolean patientConsent) {

        @Override
        public String toString() {
            return "StartRequest[aadhaarNumber=****, patientConsent=" + patientConsent + "]";
        }
    }

    public record VerifyRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be the 6-digit OTP") String otp,
            @NotBlank String mobile,
            UUID patientId) {

        @Override
        public String toString() {
            return "VerifyRequest[otp=******, patientId=" + patientId + "]";
        }
    }

    public record MobileRequest(@NotBlank String mobile) {
    }

    public record OtpRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be the 6-digit OTP") String otp) {

        @Override
        public String toString() {
            return "OtpRequest[otp=******]";
        }
    }

    public record AddressRequest(@NotBlank String abhaAddress) {
    }

    private final AbhaEnrolmentService enrolments;

    public AbhaEnrolmentController(AbhaEnrolmentService enrolments) {
        this.enrolments = enrolments;
    }

    @PostMapping
    @PreAuthorize(Roles.ABHA_DESK)
    public AbhaEnrolmentService.Started start(@Valid @RequestBody StartRequest request) {
        return enrolments.start(request.aadhaarNumber(), request.patientConsent());
    }

    @PostMapping("/{enrolmentId}/verify")
    @PreAuthorize(Roles.ABHA_DESK)
    public ResponseEntity<AbhaEnrolmentService.Created> verify(@PathVariable UUID enrolmentId,
                                                               @Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(enrolments.create(enrolmentId, request.otp(), request.mobile(), request.patientId()));
    }

    @PostMapping("/{enrolmentId}/mobile/otp")
    @PreAuthorize(Roles.ABHA_DESK)
    public Map<String, String> mobileOtp(@PathVariable UUID enrolmentId, @Valid @RequestBody MobileRequest request) {
        OtpSent sent = enrolments.requestMobileOtp(enrolmentId, request.mobile());
        return sent.message() == null ? Map.of() : Map.of("message", sent.message());
    }

    @PostMapping("/{enrolmentId}/mobile/verify")
    @PreAuthorize(Roles.ABHA_DESK)
    public Map<String, Boolean> verifyMobile(@PathVariable UUID enrolmentId, @Valid @RequestBody OtpRequest request) {
        enrolments.verifyMobile(enrolmentId, request.otp());
        return Map.of("mobileVerified", true);
    }

    @GetMapping("/{enrolmentId}/address-suggestions")
    @PreAuthorize(Roles.ABHA_DESK)
    public List<String> suggestions(@PathVariable UUID enrolmentId) {
        return enrolments.addressSuggestions(enrolmentId);
    }

    @PostMapping("/{enrolmentId}/address")
    @PreAuthorize(Roles.ABHA_DESK)
    public AbhaEnrolmentService.AddressCreated address(@PathVariable UUID enrolmentId,
                                                       @Valid @RequestBody AddressRequest request) {
        return enrolments.createAddress(enrolmentId, request.abhaAddress());
    }

    @GetMapping("/{enrolmentId}/card")
    @PreAuthorize(Roles.ABHA_DESK)
    public ResponseEntity<byte[]> card(@PathVariable UUID enrolmentId) {
        Card card = enrolments.card(enrolmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(card.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"abha-card\"")
                .body(card.content());
    }
}
