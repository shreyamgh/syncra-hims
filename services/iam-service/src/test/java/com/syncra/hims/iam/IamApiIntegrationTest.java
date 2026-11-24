package com.syncra.hims.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.iam.support.IamTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class IamApiIntegrationTest extends IamTestSupport {

    static final String GOOD = "monsoon tea at the ward";

    @Autowired
    JwtDecoder decoder;

    /** Creates an account and returns its temporary password. */
    String account(String username, UUID staffId, String... roles) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", username, "roles", List.of(roles)));
        if (staffId != null) {
            body.put("staffId", staffId);
        } else {
            body.put("displayName", "Test " + username);
        }
        return body(postJson("/api/v1/iam/users", body, ADMIN).andExpect(status().isCreated())).path("temporaryPassword").asText();
    }

    String ready(String username, String... roles) throws Exception {
        String temporary = account(username, null, roles);
        call("/api/v1/auth/password", Map.of("username", username, "currentPassword", temporary, "newPassword", GOOD))
                .andExpect(status().isNoContent());
        return GOOD;
    }

    @Test
    void aNewAccountReplacesItsTemporaryPasswordThenGetsATokenEveryServiceAccepts() throws Exception {
        UUID staff = staff("Dr Meenal Kulkarni", "DOCTOR");
        String temporary = account("dr.meenal", staff, "DOCTOR");
        assertThat(temporary).hasSize(12);
        assertThat(jdbc.sql("SELECT iam_subject FROM core.staff WHERE id = :id").param("id", staff).query(String.class).single())
                .isEqualTo("dr.meenal");
        assertThat(jdbc.sql("SELECT password_hash FROM iam.user_account WHERE username = 'dr.meenal'").query(String.class).single())
                .startsWith("$2").doesNotContain(temporary);

        call("/api/v1/auth/login", Map.of("username", "dr.meenal", "password", temporary))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        for (String weak : List.of("password123", "short", "dr.meenal is me 2026")) {
            call("/api/v1/auth/password", Map.of("username", "dr.meenal", "currentPassword", temporary, "newPassword", weak))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        }
        call("/api/v1/auth/password", Map.of("username", "DR.MEENAL ", "currentPassword", temporary, "newPassword", GOOD))
                .andExpect(status().isNoContent());

        var login = call("/api/v1/auth/login", Map.of("username", "dr.meenal", "password", GOOD)).andExpect(status().isOk());
        JsonNode session = body(login);
        assertThat(session.path("user").path("roles").get(0).asText()).isEqualTo("DOCTOR");
        Jwt token = decoder.decode(session.path("accessToken").asText());
        assertThat(token.getSubject()).isEqualTo("dr.meenal");
        assertThat(token.getClaimAsStringList("roles")).containsExactly("DOCTOR");
        assertThat(token.getClaimAsString("name")).isEqualTo("Dr Meenal Kulkarni");
        assertThat(Duration.between(Instant.now(), token.getExpiresAt())).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
        assertThat(login.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("SYNCRA_REFRESH=").contains("Path=/api/v1/auth").contains("HttpOnly").contains("SameSite=Strict").contains("Secure");
        assertThat(jdbc.sql("SELECT count(*) FROM iam.refresh_token rt JOIN iam.user_account a ON a.id = rt.user_id "
                + "WHERE a.username = 'dr.meenal' AND rt.token_hash = :raw").param("raw", refreshCookie(login).getValue())
                .query(Integer.class).single()).as("refresh tokens are stored hashed").isZero();
    }

    @Test
    void wrongPasswordsLockTheAccountAndUnknownUsersGetTheSameAnswer() throws Exception {
        String password = ready("nurse.lock", "NURSE");
        call("/api/v1/auth/login", Map.of("username", "nobody.here", "password", "whatever-123"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_CREDENTIALS"));
        for (int i = 0; i < 4; i++) {
            call("/api/v1/auth/login", Map.of("username", "nurse.lock", "password", "wrong password " + i))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_CREDENTIALS"));
        }
        call("/api/v1/auth/login", Map.of("username", "nurse.lock", "password", "wrong password 5"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        // Locked even with the right password, until the time passes or an administrator unlocks.
        call("/api/v1/auth/login", Map.of("username", "nurse.lock", "password", password))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        String id = jdbc.sql("SELECT id::text FROM iam.user_account WHERE username = 'nurse.lock'").query(String.class).single();
        postJson("/api/v1/iam/users/" + id + "/unlock", Map.of(), ADMIN).andExpect(status().isOk());
        call("/api/v1/auth/login", Map.of("username", "nurse.lock", "password", password)).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT event FROM iam.auth_event WHERE username = 'nurse.lock' ORDER BY id").query(String.class).list())
                .contains("ACCOUNT_LOCKED", "ACCOUNT_UNLOCKED", "LOGIN_SUCCEEDED");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM iam.auth_event WHERE username = 'nurse.lock'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void refreshRotatesTheCookieAndAReusedOneEndsTheSession() throws Exception {
        String password = ready("lab.rotate", "LAB_TECHNICIAN");
        Cookie first = refreshCookie(call("/api/v1/auth/login", Map.of("username", "lab.rotate", "password", password)));
        var refreshed = call("/api/v1/auth/refresh", null, first).andExpect(status().isOk());
        Cookie second = refreshCookie(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(decoder.decode(body(refreshed).path("accessToken").asText()).getSubject()).isEqualTo("lab.rotate");

        // The first cookie again (copied by someone else): the whole session ends, the newer cookie too.
        call("/api/v1/auth/refresh", null, first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_ENDED"));
        call("/api/v1/auth/refresh", null, second).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM iam.auth_event WHERE username = 'lab.rotate' AND event = 'REFRESH_REUSED'")
                .query(Integer.class).single()).isEqualTo(1);

        // Only JSON reaches the cookie endpoints (a cross-site form cannot send it).
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_FORM_URLENCODED).cookie(second))
                .andExpect(status().isUnsupportedMediaType());

        Cookie third = refreshCookie(call("/api/v1/auth/login", Map.of("username", "lab.rotate", "password", password)));
        call("/api/v1/auth/logout", null, third).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        call("/api/v1/auth/refresh", null, third).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdministratorsManageAccountsAndDisablingEndsSessions() throws Exception {
        String password = ready("clerk.off", "BILLING_CLERK");
        mvc.perform(get("/api/v1/iam/users").with(as("test.doctor", "DOCTOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/iam/users")).andExpect(status().isUnauthorized());

        Cookie session = refreshCookie(call("/api/v1/auth/login", Map.of("username", "clerk.off", "password", password)));
        String id = jdbc.sql("SELECT id::text FROM iam.user_account WHERE username = 'clerk.off'").query(String.class).single();
        postJson("/api/v1/iam/users/" + id + "/disable", Map.of(), ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        call("/api/v1/auth/refresh", null, session).andExpect(status().isUnauthorized());
        call("/api/v1/auth/login", Map.of("username", "clerk.off", "password", password))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_CREDENTIALS"));

        postJson("/api/v1/iam/users", Map.of("username", "x.bad", "displayName", "X", "roles", List.of("SUPERUSER")), ADMIN)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BAD_ROLES"));
        postJson("/api/v1/iam/users", Map.of("username", "clerk.off", "displayName", "Again", "roles", List.of("NURSE")), ADMIN)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        account("test.admin", null, "ADMINISTRATOR");
        String own = jdbc.sql("SELECT id::text FROM iam.user_account WHERE username = 'test.admin'").query(String.class).single();
        postJson("/api/v1/iam/users/" + own + "/disable", Map.of(), ADMIN).andExpect(status().isConflict());

        JsonNode reset = body(postJson("/api/v1/iam/users/" + id + "/reset-password", Map.of(), ADMIN).andExpect(status().isOk()));
        assertThat(reset.path("account").path("mustChangePassword").asBoolean()).isTrue();
        assertThat(reset.path("temporaryPassword").asText()).hasSize(12);
        assertThat(reset.path("account").has("passwordHash")).isFalse();
    }
}
