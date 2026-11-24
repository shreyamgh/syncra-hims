package com.syncra.hims.abdm.abha;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.crypto.AbhaEncryption;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmHeaders;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ABHA V3 profile login: an OTP is sent for an ABHA number, and verifying it yields a
 * short-lived user token (X-token) for reading the profile. Identifiers and OTPs are
 * RSA-OAEP encrypted with ABHA's published key, which is cached and re-fetched once if
 * ABHA rejects an encryption.
 */
@Component
public class AbhaClient {

    /** Result of an OTP request: ABHA's transaction id and its message (e.g. "OTP sent to ******1234"). */
    public record OtpSent(String txnId, String message) {
    }

    /** A successful verification: the user token stays in memory for the profile call only. */
    public record Login(String txnId, String userToken) {

        @Override
        public String toString() {
            return "Login[txnId=" + txnId + ", userToken=****]";
        }
    }

    private final AbdmClient client;
    private final AbhaKey key;

    public AbhaClient(AbdmClient client, AbhaKey key) {
        this.client = client;
        this.key = key;
    }

    public OtpSent requestOtp(AbhaLoginMethod method, String abhaNumber) {
        JsonNode answer = key.withEncryption(publicKey -> client.abhaPost(AbdmPaths.ABHA_LOGIN_REQUEST_OTP, Map.of(
                "scope", method.scope(),
                "loginHint", "abha-number",
                "loginId", AbhaEncryption.encrypt(publicKey, AbhaNumbers.hyphenated(abhaNumber)),
                "otpSystem", method.otpSystem()), Call.plain()).body());
        String txnId = text(answer, "txnId");
        if (txnId == null) {
            throw AbdmCallException.invalidResponse("no txnId in OTP response");
        }
        return new OtpSent(txnId, text(answer, "message"));
    }

    public Login verifyOtp(AbhaLoginMethod method, String txnId, String otp) {
        JsonNode answer = key.withEncryption(publicKey -> client.abhaPost(AbdmPaths.ABHA_LOGIN_VERIFY, Map.of(
                "scope", method.scope(),
                "authData", Map.of(
                        "authMethods", java.util.List.of("otp"),
                        "otp", Map.of("txnId", txnId, "otpValue", AbhaEncryption.encrypt(publicKey, otp)))),
                Call.plain()).body());
        String result = text(answer, "authResult");
        if (result != null && !"success".equalsIgnoreCase(result)) {
            throw AbdmCallException.rejected(200, null, text(answer, "message"));
        }
        String token = text(answer, "token");
        if (token == null) {
            throw AbdmCallException.invalidResponse("no token in verification response");
        }
        return new Login(text(answer, "txnId") != null ? text(answer, "txnId") : txnId, token);
    }

    public AbhaProfile profile(Login login) {
        JsonNode answer = client.abhaGet(AbdmPaths.ABHA_PROFILE,
                Call.plain().withHeader(AbdmHeaders.ABHA_USER_TOKEN, "Bearer " + login.userToken())).body();
        AbhaProfile profile = AbhaProfile.fromJson(answer);
        if (profile.abhaNumber() == null) {
            throw AbdmCallException.invalidResponse("profile without an ABHA number");
        }
        return profile;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
