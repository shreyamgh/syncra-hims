package com.syncra.hims.common.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identity of the caller, taken from the validated JWT. */
public class CurrentUser {

    public static final String SYSTEM = "system";

    /** JWT {@code sub}, or {@value #SYSTEM} outside a request. */
    public String subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getName() != null ? auth.getName() : SYSTEM;
    }

    /** First role of the caller without the {@code ROLE_} prefix, for the audit trail. */
    public Optional<String> primaryRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Optional.empty();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst();
    }

    public boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
