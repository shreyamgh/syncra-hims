package com.syncra.hims.iam.auth;

import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.iam.IamProperties;
import com.syncra.hims.iam.account.AccountRepository;
import com.syncra.hims.iam.account.AccountRepository.Account;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, session refresh, sign-out and password change.
 *
 * <ul>
 *   <li>A wrong username and a wrong password get the same answer, in the same time.</li>
 *   <li>After {@code maxFailedAttempts} wrong passwords the account locks for {@code lockout}.</li>
 *   <li>A temporary password (new account, reset) must be changed before any token is issued.</li>
 *   <li>Refresh tokens rotate on every use; a used token presented again revokes the session.</li>
 * </ul>
 */
@Service
public class AuthService {

    /** What a successful sign-in or refresh returns: the access token, and the refresh token for the cookie. */
    public record Session(String accessToken, Instant expiresAt, String refreshToken, Instant sessionEnds,
                          String username, String displayName, List<String> roles) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final Passwords passwords;
    private final TokenIssuer tokens;
    private final IamProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AccountRepository accounts, SessionRepository sessions, Passwords passwords, TokenIssuer tokens,
                       IamProperties properties, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwords = passwords;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String rawUsername, String password, String clientIp) {
        String username = normalise(rawUsername);
        Instant now = clock.instant();
        Account account = accounts.lockByUsername(username).orElse(null);
        boolean correct = passwords.matches(password, account == null ? null : account.passwordHash());
        if (account == null) {
            sessions.event(username, "LOGIN_FAILED", null, clientIp);
            throw wrongCredentials();
        }
        if (account.lockedAt(now)) {
            sessions.event(username, "LOGIN_FAILED", null, clientIp);
            throw locked(account.lockedUntil());
        }
        if (!correct) {
            failed(account, now, clientIp);
        }
        if (!"ACTIVE".equals(account.status())) {
            sessions.event(username, "LOGIN_FAILED", null, clientIp);
            throw wrongCredentials();
        }
        if (account.mustChangePassword()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                    "Choose a new password to replace the temporary one");
        }
        accounts.recordLogin(account.id(), now);
        sessions.event(username, "LOGIN_SUCCEEDED", null, clientIp);
        return start(account, UUID.randomUUID(), now, now.plus(properties.sessionTtl()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String refreshToken, String clientIp) {
        Instant now = clock.instant();
        SessionRepository.RefreshToken current = refreshToken == null ? null
                : sessions.lockByHash(sha256(refreshToken)).orElse(null);
        if (current == null) {
            throw sessionEnded();
        }
        Account account = accounts.lock(current.userId()).orElseThrow(AuthService::sessionEnded);
        if (current.revokedAt() != null) {
            if (current.replacedBy() != null) {
                // A rotated token came back: it was copied. End every session in this family.
                sessions.revokeFamily(current.familyId(), now);
                sessions.event(account.username(), "REFRESH_REUSED", null, clientIp);
            }
            throw sessionEnded();
        }
        if (!current.expiresAt().isAfter(now) || !"ACTIVE".equals(account.status()) || account.lockedAt(now)
                || account.mustChangePassword()) {
            sessions.revokeFamily(current.familyId(), now);
            throw sessionEnded();
        }
        Session next = start(account, current.familyId(), now, current.expiresAt());
        UUID nextId = sessions.lockByHash(sha256(next.refreshToken())).orElseThrow().id();
        sessions.rotate(current.id(), nextId, now);
        return next;
    }

    @Transactional
    public void logout(String refreshToken, String clientIp) {
        if (refreshToken == null) {
            return;
        }
        sessions.lockByHash(sha256(refreshToken)).ifPresent(token -> {
            sessions.revokeFamily(token.familyId(), clock.instant());
            accounts.lock(token.userId()).ifPresent(a -> sessions.event(a.username(), "LOGOUT", null, clientIp));
        });
    }

    /** Replaces a password (the current one proves who is asking); every session of the user ends. */
    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(String rawUsername, String currentPassword, String newPassword, String clientIp) {
        String username = normalise(rawUsername);
        Instant now = clock.instant();
        Account account = accounts.lockByUsername(username).orElse(null);
        boolean correct = passwords.matches(currentPassword, account == null ? null : account.passwordHash());
        if (account == null || !"ACTIVE".equals(account.status())) {
            throw wrongCredentials();
        }
        if (account.lockedAt(now)) {
            throw locked(account.lockedUntil());
        }
        if (!correct) {
            failed(account, now, clientIp);
        }
        List<String> problems = passwords.problems(newPassword, username);
        if (newPassword != null && newPassword.equals(currentPassword)) {
            problems = List.of("The new password must differ from the current one");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", String.join("; ", problems),
                    Map.of("problems", problems));
        }
        accounts.setPassword(account.id(), passwords.hash(newPassword), false, now);
        sessions.revokeAll(account.id(), now);
        sessions.event(username, "PASSWORD_CHANGED", username, clientIp);
    }

    private Session start(Account account, UUID familyId, Instant now, Instant sessionEnds) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        sessions.insert(account.id(), sha256(refresh), familyId, sessionEnds);
        TokenIssuer.AccessToken access = tokens.issue(account.username(), account.displayName(), account.roles(), now, sessionEnds);
        return new Session(access.value(), access.expiresAt(), refresh, sessionEnds, account.username(),
                account.displayName(), account.roles());
    }

    private void failed(Account account, Instant now, String clientIp) {
        int attempts = account.failedAttempts() + 1;
        if (attempts >= properties.maxFailedAttempts()) {
            Instant until = now.plus(properties.lockout());
            accounts.recordFailure(account.id(), 0, until);
            sessions.event(account.username(), "ACCOUNT_LOCKED", null, clientIp);
            throw locked(until);
        }
        accounts.recordFailure(account.id(), attempts, null);
        sessions.event(account.username(), "LOGIN_FAILED", null, clientIp);
        throw wrongCredentials();
    }

    static String normalise(String username) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return u.length() > 64 ? u.substring(0, 64) : u;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException wrongCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "WRONG_CREDENTIALS", "Wrong username or password");
    }

    private static ApiException locked(Instant until) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "Too many wrong passwords. Try again after "
                + TIME.format(until) + ", or ask the administrator to unlock the account");
    }

    private static ApiException sessionEnded() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_ENDED", "Your session has ended. Please sign in again");
    }
}
