package com.syncra.hims.iam.account;

import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.iam.account.AccountRepository.Account;
import com.syncra.hims.iam.account.AccountRepository.StaffMember;
import com.syncra.hims.iam.auth.Passwords;
import com.syncra.hims.iam.auth.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account administration: create (with a one-time temporary password), reset, disable, unlock, roles. */
@Service
public class AdminService {

    public static final Set<String> ROLES = Set.of("DOCTOR", "NURSE", "RECEPTIONIST", "BILLING_CLERK", "PHARMACIST",
            "LAB_TECHNICIAN", "RADIOGRAPHER", "RADIOLOGIST", "MRD_OFFICER", "ADMINISTRATOR");

    /** An account as administrators see it (never the hash). */
    public record AccountView(UUID id, String username, String displayName, UUID staffId, List<String> roles, String status,
                              boolean locked, Instant lockedUntil, boolean mustChangePassword, Instant lastLoginAt,
                              Instant createdAt) {
        static AccountView of(Account a, Instant now) {
            return new AccountView(a.id(), a.username(), a.displayName(), a.staffId(), a.roles(), a.status(), a.lockedAt(now),
                    a.lockedAt(now) ? a.lockedUntil() : null, a.mustChangePassword(), a.lastLoginAt(), a.createdAt());
        }
    }

    /** The temporary password is shown once, to hand to the person; only its hash is kept. */
    public record Created(AccountView account, String temporaryPassword) {
    }

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final Passwords passwords;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminService(AccountRepository accounts, SessionRepository sessions, Passwords passwords, CurrentUser currentUser,
                        Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwords = passwords;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public List<AccountView> list() {
        Instant now = clock.instant();
        return accounts.all().stream().map(a -> AccountView.of(a, now)).toList();
    }

    public List<StaffMember> staff() {
        return accounts.staff();
    }

    @Transactional
    public Created create(String rawUsername, String displayName, UUID staffId, List<String> roles) {
        String username = rawUsername.trim().toLowerCase(java.util.Locale.ROOT);
        requireRoles(roles);
        if (accounts.byUsername(username).isPresent()) {
            throw ApiException.conflict("USERNAME_TAKEN", "The username " + username + " is already in use");
        }
        String name = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (staffId != null) {
            StaffMember staff = accounts.staffMember(staffId).orElseThrow(() -> ApiException.notFound("Staff member", staffId));
            if (staff.hasAccount()) {
                throw ApiException.conflict("STAFF_HAS_ACCOUNT", staff.fullName() + " already has an account");
            }
            if (staff.iamSubject() != null && !staff.iamSubject().equals(username)) {
                throw ApiException.conflict("STAFF_SUBJECT_DIFFERS",
                        staff.fullName() + "'s records use the sign-in name " + staff.iamSubject() + "; use that username");
            }
            accounts.linkStaff(staffId, username);
            name = name == null ? staff.fullName() : name;
        }
        if (name == null) {
            throw ApiException.unprocessable("NAME_REQUIRED", "Give the person's name, or link the account to a staff member");
        }
        String temporary = passwords.temporary();
        UUID id = accounts.insert(username, passwords.hash(temporary), name, staffId, roles.stream().distinct().sorted().toList(),
                currentUser.subject());
        sessions.event(username, "ACCOUNT_CREATED", currentUser.subject(), null);
        return new Created(AccountView.of(accounts.lock(id).orElseThrow(), clock.instant()), temporary);
    }

    @Transactional
    public Created resetPassword(UUID id) {
        Account account = require(id);
        String temporary = passwords.temporary();
        Instant now = clock.instant();
        accounts.setPassword(id, passwords.hash(temporary), true, now);
        sessions.revokeAll(id, now);
        sessions.event(account.username(), "PASSWORD_RESET", currentUser.subject(), null);
        return new Created(AccountView.of(accounts.lock(id).orElseThrow(), now), temporary);
    }

    @Transactional
    public AccountView setStatus(UUID id, boolean active) {
        Account account = require(id);
        if (!active && account.username().equals(currentUser.subject())) {
            throw ApiException.conflict("OWN_ACCOUNT", "You cannot disable your own account");
        }
        accounts.setStatus(id, active ? "ACTIVE" : "DISABLED");
        if (!active) {
            sessions.revokeAll(id, clock.instant());
        }
        sessions.event(account.username(), active ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", currentUser.subject(), null);
        return AccountView.of(accounts.lock(id).orElseThrow(), clock.instant());
    }

    @Transactional
    public AccountView unlock(UUID id) {
        Account account = require(id);
        accounts.unlock(id);
        sessions.event(account.username(), "ACCOUNT_UNLOCKED", currentUser.subject(), null);
        return AccountView.of(accounts.lock(id).orElseThrow(), clock.instant());
    }

    @Transactional
    public AccountView setRoles(UUID id, List<String> roles) {
        Account account = require(id);
        requireRoles(roles);
        if (account.username().equals(currentUser.subject()) && !roles.contains("ADMINISTRATOR")) {
            throw ApiException.conflict("OWN_ACCOUNT", "You cannot remove your own administrator role");
        }
        accounts.setRoles(id, roles.stream().distinct().sorted().toList());
        // New roles apply from the next sign-in: end current sessions so nobody keeps the old ones.
        sessions.revokeAll(id, clock.instant());
        sessions.event(account.username(), "ROLES_CHANGED", currentUser.subject(), null);
        return AccountView.of(accounts.lock(id).orElseThrow(), clock.instant());
    }

    private Account require(UUID id) {
        return accounts.lock(id).orElseThrow(() -> ApiException.notFound("Account", id));
    }

    private static void requireRoles(List<String> roles) {
        if (roles == null || roles.isEmpty() || roles.size() > 4 || !ROLES.containsAll(roles)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BAD_ROLES", "Choose one to four roles from: " + String.join(", ", ROLES.stream().sorted().toList()));
        }
    }
}
