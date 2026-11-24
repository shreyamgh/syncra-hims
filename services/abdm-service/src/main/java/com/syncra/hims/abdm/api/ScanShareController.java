package com.syncra.hims.abdm.api;

import com.syncra.hims.abdm.abha.AbhaLinker.LinkedAbha;
import com.syncra.hims.abdm.security.Roles;
import com.syncra.hims.abdm.share.PatientShareRepository.Share;
import com.syncra.hims.abdm.share.ScanShareService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Milestone 1 — the Scan &amp; Share queue at the registration desk.
 *
 * <pre>
 * GET  /api/v1/abdm/scan-share?context=OPD-1      today's waiting profiles (by counter)
 * POST /api/v1/abdm/scan-share/{id}/register      patient registered/found: attach the ABHA as verified
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/abdm/scan-share")
public class ScanShareController {

    public record RegisterRequest(@NotNull UUID patientId) {
    }

    private final ScanShareService scanShare;

    public ScanShareController(ScanShareService scanShare) {
        this.scanShare = scanShare;
    }

    @GetMapping
    @PreAuthorize(Roles.ABHA_DESK)
    public ResponseEntity<List<Share>> pending(@RequestParam(required = false) String context) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(scanShare.pending(context));
    }

    @PostMapping("/{shareId}/register")
    @PreAuthorize(Roles.ABHA_DESK)
    public LinkedAbha register(@PathVariable UUID shareId, @Valid @RequestBody RegisterRequest request) {
        return scanShare.register(shareId, request.patientId());
    }
}
