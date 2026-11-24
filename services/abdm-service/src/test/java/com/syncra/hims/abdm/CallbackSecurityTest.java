package com.syncra.hims.abdm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.syncra.hims.abdm.support.AbdmTestSupport;
import com.syncra.hims.abdm.support.FakeAbdm;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * ABDM's callbacks and the staff API trust different issuers: a callback needs a JWT signed by
 * the ABDM gateway, the staff API a Syncra JWT, and neither token opens the other door.
 */
class CallbackSecurityTest extends AbdmTestSupport {

    static final Map<String, Object> SHARE = Map.of("metaData", Map.of("hipId", HIP_ID, "context", "SEC"),
            "profile", Map.of("patient", Map.of("abhaAddress", "sec.test@sbx", "name", "Sec Test", "gender", "M")));

    @Test
    void callbacksWithoutAGatewaySignedTokenAreRejected() throws Exception {
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), SHARE, null).andExpect(status().isUnauthorized());
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), SHARE, FakeAbdm.forgedToken())
                .andExpect(status().isUnauthorized());
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), SHARE, ABDM.expiredGatewayToken())
                .andExpect(status().isUnauthorized());
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), SHARE, staffToken()).andExpect(status().isUnauthorized());
        callback("/api/v3/hip/patient/share", UUID.randomUUID(), SHARE).andExpect(status().isAccepted());
    }

    @Test
    void theGatewayTokenDoesNotOpenTheStaffApi() throws Exception {
        mvc.perform(get("/api/v1/abdm/scan-share").header(HttpHeaders.AUTHORIZATION, "Bearer " + ABDM.gatewayToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/abdm/scan-share").header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken()))
                .andExpect(status().isOk());
    }

    @Test
    void callbacksAreCheckedForShape() throws Exception {
        mvc.perform(post("/api/v3/hip/patient/share").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("REQUEST-ID", "not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ABDM.gatewayToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_ID"));
        mvc.perform(post("/api/v3/hip/patient/share").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("REQUEST-ID", UUID.randomUUID().toString()).header("X-HIP-ID", "IN0000000000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ABDM.gatewayToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_HIP"));
        mvc.perform(get("/api/v3/hip/patient/share").header(HttpHeaders.AUTHORIZATION, "Bearer " + ABDM.gatewayToken()))
                .andExpect(status().isForbidden());
    }

    /** A real HS256 Syncra staff token (not the MockMvc shortcut), so the decoder is exercised. */
    private static String staffToken() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject("test.reception").issuer("https://auth.syncra.local").audience("syncra-hims")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)).claim("roles", List.of("RECEPTIONIST"))
                .build());
        jwt.sign(new MACSigner("abdm-test-secret-abdm-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
