package com.syncra.hims.iam.auth;

import com.syncra.hims.iam.IamProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public sign-in endpoints (no token needed to call them):
 * <pre>
 * GET  /api/v1/auth/config     what the sign-in page may offer
 * POST /api/v1/auth/login      username + password: access token, refresh cookie
 * POST /api/v1/auth/refresh    refresh cookie: new access token, rotated cookie
 * POST /api/v1/auth/logout     ends the session, clears the cookie
 * POST /api/v1/auth/password   username + current + new password
 * </pre>
 * The refresh cookie is HttpOnly, SameSite=Strict and limited to {@code /api/v1/auth}; the
 * endpoints that read it accept JSON only, which a cross-site form cannot send.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    public static final String REFRESH_COOKIE = "SYNCRA_REFRESH";

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String password) {
    }

    public record PasswordRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 256) String currentPassword,
                                  @NotBlank @Size(max = 256) String newPassword) {
    }

    public record SessionResponse(String accessToken, Instant expiresAt, Instant sessionEnds, User user) {
    }

    public record User(String username, String name, List<String> roles) {
    }

    public record Config(boolean developmentSignIn, int minPasswordLength) {
    }

    private final AuthService auth;
    private final IamProperties properties;

    public AuthController(AuthService auth, IamProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @GetMapping("/config")
    public Config config() {
        return new Config(properties.developmentSignIn(), Passwords.MIN_LENGTH);
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest r, HttpServletRequest request) {
        return issued(auth.login(r.username(), r.password(), clientIp(request)));
    }

    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                                   HttpServletRequest request) {
        return issued(auth.refresh(token, clientIp(request)));
    }

    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                       HttpServletRequest request) {
        auth.logout(token, clientIp(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordRequest r, HttpServletRequest request) {
        auth.changePassword(r.username(), r.currentPassword(), r.newPassword(), clientIp(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseEntity<SessionResponse> issued(AuthService.Session s) {
        Duration remaining = Duration.between(Instant.now(), s.sessionEnds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), remaining.isNegative() ? Duration.ZERO : remaining).toString())
                .body(new SessionResponse(s.accessToken(), s.expiresAt(), s.sessionEnds(),
                        new User(s.username(), s.displayName(), s.roles())));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/api/v1/auth").maxAge(maxAge).build();
    }

    /** The browser's address as the gateway saw it (first X-Forwarded-For entry), for the event log only. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip != null && ip.matches("^[0-9a-fA-F:.]{2,45}$") ? ip : null;
    }
}
