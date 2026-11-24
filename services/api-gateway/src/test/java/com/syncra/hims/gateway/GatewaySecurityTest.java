package com.syncra.hims.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Edge-security contract of the gateway, exercised over real HTTP against the
 * embedded Netty server. Upstreams and Redis point at closed ports so the tests
 * also cover rate-limiter fail-open and circuit-breaker fallback behaviour.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "syncra.gateway.jwt.secret=" + GatewaySecurityTest.SECRET,
            "syncra.gateway.jwt.issuer=" + GatewaySecurityTest.ISSUER,
            "syncra.gateway.jwt.audience=" + GatewaySecurityTest.AUDIENCE,
            "syncra.gateway.cors.allowed-origins=http://localhost:4200",
            "syncra.gateway.orthanc.uri=http://127.0.0.1:1",
            "syncra.gateway.orthanc.username=pacs",
            "syncra.gateway.orthanc.password=pacs-secret",
            "SYNCRA_ROUTES_PATIENT_SERVICE_URI=http://127.0.0.1:1",
            "SYNCRA_ROUTES_BILLING_SERVICE_URI=http://127.0.0.1:1",
            "SYNCRA_ROUTES_ABDM_SERVICE_URI=http://127.0.0.1:1",
            "SYNCRA_ROUTES_AI_SERVICE_URI=http://127.0.0.1:1",
            "SYNCRA_ROUTES_AI_SERVICE_WS_URI=ws://127.0.0.1:1",
            "spring.data.redis.port=1",
            "management.server.port=0"
        })
class GatewaySecurityTest {

    static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    static final String ISSUER = "https://auth.test";
    static final String AUDIENCE = "syncra-hims";

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void allowForFailOpenAndFallbackLatency() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }

    @Test
    void rejectsRequestWithoutToken() {
        client.get().uri("/api/v1/patients")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "^Bearer.*");
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        String forged = token("another-secret-another-secret-0123456789ab", AUDIENCE, inOneHour());
        client.get().uri("/api/v1/patients")
                .headers(h -> h.setBearerAuth(forged))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenForAnotherAudience() {
        client.get().uri("/api/v1/patients")
                .headers(h -> h.setBearerAuth(token(SECRET, "some-other-app", inOneHour())))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsExpiredToken() {
        client.get().uri("/api/v1/patients")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, Instant.now().minus(Duration.ofMinutes(10)))))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenIsRoutedAndUnavailableUpstreamYieldsProblemDetail() {
        client.get().uri("/api/v1/patients/SYNC-20261120-0001")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, inOneHour())))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "15")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("patient-service");
    }

    @Test
    void accessTokenQueryParameterIsRejectedOutsideWebSocketPaths() {
        client.get().uri("/api/v1/patients?access_token={t}", token(SECRET, AUDIENCE, inOneHour()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void accessTokenQueryParameterIsAcceptedOnWebSocketPaths() {
        client.get().uri("/ws/ai/dictation?access_token={t}", token(SECRET, AUDIENCE, inOneHour()))
                .exchange()
                // Authenticated; this is not a real upgrade request so the WS proxy rejects it.
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
    }

    @Test
    void dictationWebSocketIsLimitedToClinicians() {
        client.get().uri("/ws/ai/dictation?access_token={t}", token(SECRET, AUDIENCE, inOneHour(), "RECEPTIONIST"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void imageViewerSessionIsAReadOnlyCookieForClinicalStaffUnderPacsOnly() {
        String doctor = token(SECRET, AUDIENCE, inOneHour());
        client.post().uri("/pacs/session").headers(h -> h.setBearerAuth(doctor))
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie)
                        .startsWith("SYNCRA_PACS=" + doctor + ";")
                        .contains("Path=/pacs").contains("HttpOnly").contains("SameSite=Strict").contains("Secure"));
        client.post().uri("/pacs/session")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, inOneHour(), "BILLING_CLERK")))
                .exchange().expectStatus().isForbidden();

        // Accepted for reads under /pacs (the closed upstream then fails, but not on auth)...
        client.get().uri("/pacs/dicom-web/studies").cookie("SYNCRA_PACS", doctor)
                .exchange().expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        client.get().uri("/pacs/ohif/viewer?StudyInstanceUIDs=1.2.3").cookie("SYNCRA_PACS", doctor)
                .exchange().expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        // ...never for writes, never outside /pacs, and only for clinical roles.
        client.post().uri("/pacs/dicom-web/studies").cookie("SYNCRA_PACS", doctor)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/patients/SYNC-20261120-0001").cookie("SYNCRA_PACS", doctor)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/pacs/dicom-web/studies")
                .cookie("SYNCRA_PACS", token(SECRET, AUDIENCE, inOneHour(), "BILLING_CLERK"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/pacs/dicom-web/studies").cookie("SYNCRA_PACS", "not-a-token")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void signInNeedsNoTokenAndIgnoresAnExpiredOne() {
        // Routed to the IAM service (closed here, so the fallback answers), never refused for want of a token.
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.service").isEqualTo("iam-service");
        client.post().uri("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, Instant.now().minusSeconds(60))))
                .exchange().expectStatus().isEqualTo(503);
        // Account administration is an ordinary authenticated API.
        client.get().uri("/api/v1/iam/users").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void emrRequestsAreRoutedToThePatientService() {
        client.get().uri("/api/v1/emr/encounters/2b9c3c3e-0000-4000-8000-000000000001")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, inOneHour())))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.service").isEqualTo("patient-service");
    }

    @Test
    void billingRequestsAreRoutedToTheBillingService() {
        client.get().uri("/api/v1/billing/tariffs")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, inOneHour(), "BILLING_CLERK")))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.service").isEqualTo("billing-service");
    }

    @Test
    void abdmStaffApiNeedsASyncraToken() {
        client.post().uri("/api/v1/abdm/abha/otp").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/abdm/scan-share")
                .headers(h -> h.setBearerAuth(token(SECRET, AUDIENCE, inOneHour(), "RECEPTIONIST")))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.service").isEqualTo("abdm-service");
    }

    @Test
    void abdmCallbacksPassThroughToTheAbdmServiceWithTheirOwnToken() {
        // Not a Syncra token: the gateway must not reject it; the ABDM Service verifies it.
        client.post().uri("/api/v3/consent/request/hip/notify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2lnbmF0dXJl")
                .header("REQUEST-ID", "5f6d2a9e-0d7c-4a8e-9b1a-2c3d4e5f6a7b")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.service").isEqualTo("abdm-service");
        // Only POST callbacks are exempt from the Syncra token check
        client.get().uri("/api/v3/consent/request/hip/notify")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void otherHospitalsPushRecordsWithoutASyncraToken() {
        // The HIU data-push URL: no credential in ABDM's protocol; the ABDM Service checks the URL's secret.
        client.post().uri("/hiu/v3/data/push/" + "a".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.service").isEqualTo("abdm-service");
        client.get().uri("/hiu/v3/data/push/" + "a".repeat(43))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void echoesSafeRequestIdAndReplacesUnsafeOne() {
        client.get().uri("/api/v1/patients")
                .header("X-Request-Id", "opd-desk-42.abc_1")
                .exchange()
                .expectHeader().valueEquals("X-Request-Id", "opd-desk-42.abc_1");

        for (String unsafe : List.of("bad id <script>", "x".repeat(65))) {
            client.get().uri("/api/v1/patients")
                    .header("X-Request-Id", unsafe)
                    .exchange()
                    .expectHeader().valueMatches("X-Request-Id", "^[0-9a-f-]{36}$");
        }
    }

    @Test
    void corsPreflightAllowsConfiguredOriginOnly() {
        client.options().uri("/api/v1/patients")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");

        client.options().uri("/api/v1/patients")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    private static Instant inOneHour() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    private static String token(String secret, String audience, Instant expiresAt) {
        return token(secret, audience, expiresAt, "DOCTOR");
    }

    private static String token(String secret, String audience, Instant expiresAt, String role) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("dr.sharma")
                .audience(List.of(audience))
                .issuedAt(expiresAt.minus(Duration.ofHours(2)))
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
