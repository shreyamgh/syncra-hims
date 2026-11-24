package com.syncra.hims.gateway.web;

import com.syncra.hims.gateway.config.GatewayProperties;
import com.syncra.hims.gateway.security.WebSocketAwareBearerTokenConverter;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Opens the image viewer for a signed-in clinician. A browser tab opening the viewer cannot
 * send an {@code Authorization} header, so the caller's (already validated) token is set as a
 * cookie that the gateway accepts only for read-only requests under {@code /pacs}: HttpOnly
 * (scripts can't read it), SameSite=Strict, path {@code /pacs}, and no longer than the token.
 *
 * <pre>
 * POST   /pacs/session   bearer token required; sets the viewer cookie (204)
 * DELETE /pacs/session   clears it (204)
 * </pre>
 */
@RestController
public class PacsSessionController {

    private static final Duration LONGEST = Duration.ofHours(12);

    private final boolean secure;

    public PacsSessionController(GatewayProperties properties) {
        this.secure = properties.pacsCookieSecure();
    }

    @PostMapping("/pacs/session")
    public Mono<ResponseEntity<Void>> open(@AuthenticationPrincipal Jwt jwt) {
        Duration lifetime = Duration.between(Instant.now(), jwt.getExpiresAt());
        if (lifetime.compareTo(LONGEST) > 0) {
            lifetime = LONGEST;
        }
        return Mono.just(ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(jwt.getTokenValue(), lifetime).toString()).build());
    }

    @DeleteMapping("/pacs/session")
    public Mono<ResponseEntity<Void>> close() {
        return Mono.just(ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(WebSocketAwareBearerTokenConverter.PACS_COOKIE, value)
                .httpOnly(true).secure(secure).sameSite("Strict").path("/pacs").maxAge(maxAge).build();
    }
}
