package com.syncra.hims.patient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.syncra.hims.patient.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The service re-validates real HS256 tokens itself (it does not trust the gateway blindly). */
class TokenValidationIntegrationTest extends IntegrationTestSupport {

    @Test
    void acceptsAValidTokenAndMapsItsRoles() throws Exception {
        mvc.perform(get("/api/v1/opd/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(JWT_AUDIENCE, List.of("RECEPTIONIST"), 1)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void rejectsWrongAudienceExpiredAndMissingTokens() throws Exception {
        mvc.perform(get("/api/v1/opd/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("another-app", List.of("NURSE"), 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/opd/departments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(JWT_AUDIENCE, List.of("NURSE"), -1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/opd/departments")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesFromTheTokenAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/ipd/admissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(JWT_AUDIENCE, List.of("PHARMACIST"), 1)))
                .andExpect(status().isForbidden());
    }

    private static String token(String audience, List<String> roles, int hoursValid) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant expiry = Instant.now().plus(Duration.ofHours(hoursValid));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JWT_ISSUER)
                .subject("token.test")
                .audience(List.of(audience))
                .issuedAt(expiry.minus(Duration.ofHours(2)))
                .expiresAt(expiry)
                .claim("roles", roles)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
