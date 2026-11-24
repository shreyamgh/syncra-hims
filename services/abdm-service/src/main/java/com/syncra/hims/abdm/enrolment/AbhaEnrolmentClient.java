package com.syncra.hims.abdm.enrolment;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.abha.AbhaClient.OtpSent;
import com.syncra.hims.abdm.abha.AbhaKey;
import com.syncra.hims.abdm.abha.AbhaProfile;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.crypto.AbhaEncryption;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmHeaders;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ABHA V3 enrolment by Aadhaar: OTP to the Aadhaar-linked mobile, account creation (or the
 * existing ABHA for that Aadhaar), optional verification of a different communication mobile,
 * ABHA address selection, and the ABHA card. Aadhaar numbers, mobiles and OTPs are RSA-OAEP
 * encrypted with ABHA's key.
 */
@Component
public class AbhaEnrolmentClient {

    /** ABDM's consent code for creating an ABHA with Aadhaar. */
    public static final String CONSENT_CODE = "abha-enrollment";
    private static final List<String> ENROL = List.of("abha-enrol");
    private static final List<String> ENROL_MOBILE = List.of("abha-enrol", "mobile-verify");
    private static final String TRANSACTION_ID = "Transaction_Id";

    /**
     * @param isNew          false when ABHA already had an account for this Aadhaar (it is returned instead)
     * @param mobileVerified the requested mobile is the ABHA communication mobile (it was Aadhaar-linked)
     */
    public record Enrolled(String txnId, String userToken, AbhaProfile profile, boolean isNew, boolean mobileVerified) {

        @Override
        public String toString() {
            return "Enrolled[txnId=" + txnId + ", userToken=****, isNew=" + isNew + "]";
        }
    }

    public record Card(byte[] content, String contentType) {
    }

    private final AbdmClient client;
    private final AbhaKey key;
    private final AbdmProperties properties;

    public AbhaEnrolmentClient(AbdmClient client, AbhaKey key, AbdmProperties properties) {
        this.client = client;
        this.key = key;
        this.properties = properties;
    }

    public OtpSent requestAadhaarOtp(String aadhaar) {
        JsonNode answer = key.withEncryption(publicKey -> client.abhaPost(AbdmPaths.ABHA_ENROL_REQUEST_OTP, Map.of(
                "txnId", "",
                "scope", ENROL,
                "loginHint", "aadhaar",
                "loginId", AbhaEncryption.encrypt(publicKey, aadhaar),
                "otpSystem", "aadhaar"), Call.plain()).body());
        return otpSent(answer);
    }

    public Enrolled enrolByAadhaar(String txnId, String otp, String mobile) {
        JsonNode answer = key.withEncryption(publicKey -> {
            Map<String, Object> otpData = new LinkedHashMap<>();
            otpData.put("txnId", txnId);
            otpData.put("otpValue", AbhaEncryption.encrypt(publicKey, otp));
            otpData.put("mobile", mobile);
            return client.abhaPost(AbdmPaths.ABHA_ENROL_BY_AADHAAR, Map.of(
                    "authData", Map.of("authMethods", List.of("otp"), "otp", otpData),
                    "consent", Map.of("code", CONSENT_CODE, "version", properties.abha().enrolmentConsentVersion())),
                    Call.plain()).body();
        });
        JsonNode account = answer.path("ABHAProfile");
        if (account.isMissingNode() || account.isNull()) {
            throw AbdmCallException.invalidResponse("no ABHAProfile in enrolment response");
        }
        AbhaProfile profile = AbhaProfile.fromJson(account);
        String token = answer.at("/tokens/token").asText(null);
        if (profile.abhaNumber() == null || token == null) {
            throw AbdmCallException.invalidResponse("enrolment response without ABHA number or token");
        }
        boolean mobileVerified = mobile.equals(profile.mobile());
        return new Enrolled(answer.path("txnId").asText(txnId), token, profile,
                !answer.has("isNew") || answer.path("isNew").asBoolean(true), mobileVerified);
    }

    public OtpSent requestMobileOtp(String txnId, String mobile) {
        JsonNode answer = key.withEncryption(publicKey -> client.abhaPost(AbdmPaths.ABHA_ENROL_REQUEST_OTP, Map.of(
                "txnId", txnId,
                "scope", ENROL_MOBILE,
                "loginHint", "mobile",
                "loginId", AbhaEncryption.encrypt(publicKey, mobile),
                "otpSystem", "abdm"), Call.plain()).body());
        return otpSent(answer);
    }

    public void verifyMobile(String txnId, String otp) {
        JsonNode answer = key.withEncryption(publicKey -> client.abhaPost(AbdmPaths.ABHA_ENROL_AUTH_BY_ABDM, Map.of(
                "scope", ENROL_MOBILE,
                "authData", Map.of("authMethods", List.of("otp"),
                        "otp", Map.of("txnId", txnId, "otpValue", AbhaEncryption.encrypt(publicKey, otp)))),
                Call.plain()).body());
        String result = answer.path("authResult").asText("");
        if (!"success".equalsIgnoreCase(result)) {
            throw AbdmCallException.rejected(200, null, answer.path("message").asText("Mobile verification failed"));
        }
    }

    public List<String> addressSuggestions(String txnId) {
        JsonNode answer = client.abhaGet(AbdmPaths.ABHA_ENROL_ADDRESS_SUGGESTIONS,
                Call.plain().withHeader(TRANSACTION_ID, txnId)).body();
        List<String> suggestions = new ArrayList<>();
        answer.path("abhaAddressList").forEach(a -> suggestions.add(a.asText()));
        return suggestions;
    }

    /** Creates the ABHA address (without the {@code @sbx}/{@code @abdm} suffix); returns it in full. */
    public String createAddress(String txnId, String address) {
        JsonNode answer = client.abhaPost(AbdmPaths.ABHA_ENROL_ADDRESS,
                Map.of("txnId", txnId, "abhaAddress", address, "preferred", 1), Call.plain()).body();
        String created = answer.path("preferredAbhaAddress").asText(null);
        if (created == null || created.isBlank()) {
            throw AbdmCallException.invalidResponse("no preferredAbhaAddress in response");
        }
        return created;
    }

    public Card card(String userToken) {
        AbdmClient.Response response = client.abhaGet(AbdmPaths.ABHA_CARD, Call.plain()
                .withHeader(AbdmHeaders.ABHA_USER_TOKEN, "Bearer " + userToken)
                .withHeader("Accept", "image/png, image/jpeg, application/pdf"));
        if (response.raw() == null || response.raw().length == 0) {
            throw AbdmCallException.invalidResponse("empty ABHA card");
        }
        return new Card(response.raw(), response.contentType() == null ? "image/png" : response.contentType());
    }

    private static OtpSent otpSent(JsonNode answer) {
        String txnId = answer.path("txnId").asText(null);
        if (txnId == null || txnId.isBlank()) {
            throw AbdmCallException.invalidResponse("no txnId in OTP response");
        }
        return new OtpSent(txnId, answer.path("message").asText(null));
    }
}
