package com.syncra.hims.abdm.api;

import com.syncra.hims.abdm.abha.AbhaLinker.LinkedAbha;
import com.syncra.hims.abdm.abha.AbhaLoginMethod;
import com.syncra.hims.abdm.abha.AbhaService;
import com.syncra.hims.abdm.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Milestone 1 — ABHA verification at the desk.
 *
 * <pre>
 * POST /api/v1/abdm/abha/otp                 send an OTP for an ABHA number
 * POST /api/v1/abdm/abha/verify              verify it; returns the ABDM profile (and links, if patientId given)
 * POST /api/v1/abdm/patients/{id}/abha       attach a verified ABHA to a (newly registered) patient
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/abdm")
public class AbhaController {

    public record OtpRequest(@NotNull AbhaLoginMethod method, @NotBlank String abhaNumber) {
    }

    public record VerifyRequest(
            @NotNull AbhaLoginMethod method,
            @NotBlank String txnId,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be the 6-digit OTP") String otp,
            @NotBlank String abhaNumber,
            UUID patientId) {

        @Override
        public String toString() {
            return "VerifyRequest[method=" + method + ", txnId=" + txnId + ", otp=******]";
        }
    }

    public record LinkRequest(@NotNull UUID verificationId) {
    }

    private final AbhaService abha;

    public AbhaController(AbhaService abha) {
        this.abha = abha;
    }

    @PostMapping("/abha/otp")
    @PreAuthorize(Roles.ABHA_DESK)
    public AbhaService.OtpRequested requestOtp(@Valid @RequestBody OtpRequest request) {
        return abha.requestOtp(request.method(), request.abhaNumber());
    }

    @PostMapping("/abha/verify")
    @PreAuthorize(Roles.ABHA_DESK)
    public ResponseEntity<AbhaService.Verified> verify(@Valid @RequestBody VerifyRequest request) {
        AbhaService.Verified verified = abha.verify(request.method(), request.txnId(), request.otp(),
                request.abhaNumber(), request.patientId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(verified);
    }

    @PostMapping("/patients/{patientId}/abha")
    @PreAuthorize(Roles.ABHA_DESK)
    public LinkedAbha link(@PathVariable UUID patientId, @Valid @RequestBody LinkRequest request) {
        return abha.link(patientId, request.verificationId());
    }
}
