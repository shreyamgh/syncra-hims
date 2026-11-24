package com.syncra.hims.abdm.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ABDM integration settings ({@code syncra.abdm.*}), validated at startup.
 *
 * <p>With {@code enabled=false} (the default, as no hospital has sandbox credentials
 * on day one) the service still builds FHIR records and care contexts, but every
 * call to ABDM answers 503 and ABDM callbacks are refused.
 *
 * @param enabled   master switch for all traffic to and from ABDM
 * @param cmId      consent-manager id sent as {@code X-CM-ID}: {@code sbx} (sandbox) or {@code abdm}
 * @param hipId     this facility's Health Facility Registry (HFR) id, used as the HIP id
 * @param hipName   facility name shown to patients in their PHR app
 */
@Validated
@ConfigurationProperties(prefix = "syncra.abdm")
public record AbdmProperties(
        boolean enabled,
        @NotBlank String cmId,
        String hipId,
        String hipName,
        @Valid @NotNull Gateway gateway,
        @Valid @NotNull Abha abha,
        @Valid @NotNull @DefaultValue Callbacks callbacks,
        @Valid @NotNull @DefaultValue DataPush dataPush,
        @Valid @NotNull @DefaultValue Linking linking,
        @Valid @NotNull @DefaultValue ScanShare scanShare,
        @Valid @NotNull @DefaultValue Discovery discovery,
        @Valid @NotNull @DefaultValue Otp otp,
        @Valid @NotNull @DefaultValue Hiu hiu,
        @Valid @NotNull Fhir fhir) {

    public AbdmProperties {
        if (enabled) {
            require(hipId, "syncra.abdm.hip-id (the facility's HFR id)");
            require(gateway.clientId(), "syncra.abdm.gateway.client-id");
            require(gateway.clientSecret(), "syncra.abdm.gateway.client-secret");
        }
    }

    /** {@code hipId}, or a placeholder in FHIR records while ABDM is not configured. */
    public String hipIdOrPlaceholder() {
        return hipId == null || hipId.isBlank() ? "HIP-NOT-CONFIGURED" : hipId;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when syncra.abdm.enabled=true");
        }
    }

    /**
     * Health Information Exchange - Consent Manager gateway.
     *
     * @param baseUrl      {@code https://dev.abdm.gov.in/api/hiecm} (sandbox) or {@code https://apis.abdm.gov.in/api/hiecm}
     * @param clientId     client id issued with the sandbox / production credentials
     * @param clientSecret its secret
     */
    public record Gateway(
            @NotNull URI baseUrl,
            String clientId,
            String clientSecret,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("20s") Duration readTimeout) {

        @Override
        public String toString() {
            return "Gateway[baseUrl=" + baseUrl + ", clientId=" + clientId + ", clientSecret=****]";
        }
    }

    /**
     * ABHA (health id) service: {@code https://abhasbx.abdm.gov.in/abha/api} in the sandbox.
     *
     * @param enrolmentConsentVersion version of ABDM's consent text the patient agrees to when an
     *                                ABHA is created with Aadhaar (sent as {@code consent.version})
     */
    public record Abha(@NotNull URI baseUrl, @NotBlank @DefaultValue("1.4") String enrolmentConsentVersion) {
    }

    /**
     * Verification of ABDM's calls to this HIP: a JWT signed with a key from {@code jwksUri}.
     *
     * @param jwksUri       ABDM's public keys; defaults to {@code <gateway>/gateway/v3/certs}
     * @param issuer        when set, the required {@code iss} claim
     * @param algorithms    accepted JWS algorithms
     */
    public record Callbacks(URI jwksUri, String issuer, @NotNull @DefaultValue("RS256") List<String> algorithms) {
    }

    /**
     * Pushing encrypted records to the HIU's {@code dataPushUrl}.
     *
     * @param requireHttps        refuse plain-HTTP and private-network push URLs (keep on in production)
     * @param sendSessionToken    send this HIP's gateway session token as {@code Authorization}; off by
     *                            default, because the URL comes from the request and the token would let
     *                            its owner call ABDM as this HIP
     * @param entriesPerPage      care-context records per push request
     * @param keyValidity         validity advertised for this HIP's one-time ECDH public key
     */
    public record DataPush(
            @DefaultValue("true") boolean requireHttps,
            @DefaultValue("false") boolean sendSessionToken,
            @Min(1) @Max(100) @DefaultValue("10") int entriesPerPage,
            @NotNull @DefaultValue("1h") Duration keyValidity) {
    }

    /**
     * This hospital as a Health Information User (HIU), viewing records other facilities hold.
     * The HIU id is the facility's HFR id ({@code hip-id}).
     *
     * @param publicUrl        public HTTPS base URL of this bridge; HIPs push records to
     *                         {@code <publicUrl>/hiu/v3/data/push/<one-time token>}. Without it no
     *                         data is requested.
     * @param keyValidity      life of the one-time ECDH key of a data request (kept in memory only)
     * @param maxRetentionDays longest a doctor may keep records received (the consent's data-erase time)
     * @param maxPushBytes     largest data-push request accepted
     */
    public record Hiu(
            URI publicUrl,
            @NotNull @DefaultValue("1h") Duration keyValidity,
            @Min(1) @Max(365) @DefaultValue("30") int maxRetentionDays,
            @Min(65_536) @Max(52_428_800) @DefaultValue("10485760") int maxPushBytes) {

        public boolean canReceive() {
            return publicUrl != null && !publicUrl.toString().isBlank();
        }
    }

    /** @param linkTokenTtl how long a link token from ABDM is reused for further care contexts */
    public record Linking(@NotNull @DefaultValue("25m") Duration linkTokenTtl) {
    }

    /**
     * Scan &amp; Share at the registration desk.
     *
     * @param tokenValidity how long the queue number shown in the patient's app stays valid
     * @param retention     shared profiles are deleted after this long
     */
    public record ScanShare(
            @NotNull @DefaultValue("30m") Duration tokenValidity,
            @NotNull @DefaultValue("24h") Duration retention) {
    }

    /**
     * Patient-initiated discovery from the PHR app.
     *
     * @param validity      how long a discovery result can be used to start linking
     * @param maxEncounters most recent encounters considered when offering care contexts
     */
    public record Discovery(
            @NotNull @DefaultValue("30m") Duration validity,
            @Min(1) @Max(500) @DefaultValue("50") int maxEncounters) {
    }

    /**
     * The OTP a patient confirms before records are linked from the PHR app, sent by SMS to the
     * mobile registered with the hospital through the hospital's SMS provider. Without
     * {@code smsUrl} patient-initiated linking answers that OTP delivery is not configured.
     *
     * @param smsUrl        provider endpoint receiving {@code {to, message, senderId, templateId, variables}}
     * @param smsAuthHeader header carrying the provider credential
     * @param smsAuthValue  the credential
     * @param smsSenderId   DLT-registered sender id (header)
     * @param smsTemplateId DLT-registered template id of {@code message}
     * @param message       SMS text; {@code {otp}}, {@code {hospital}} and {@code {minutes}} are filled in
     * @param validity      OTP lifetime
     * @param maxAttempts   wrong entries before the challenge is void
     * @param maxPerHour    OTPs sent per patient per hour (limits SMS flooding through ABDM)
     */
    public record Otp(
            URI smsUrl,
            @DefaultValue("Authorization") String smsAuthHeader,
            String smsAuthValue,
            String smsSenderId,
            String smsTemplateId,
            @NotBlank @DefaultValue("{otp} is the OTP to link your {hospital} health records to your ABHA. "
                    + "Valid for {minutes} minutes. Do not share it with anyone.") String message,
            @NotNull @DefaultValue("10m") Duration validity,
            @Min(1) @Max(10) @DefaultValue("3") int maxAttempts,
            @Min(1) @Max(50) @DefaultValue("5") int maxPerHour) {

        public boolean smsConfigured() {
            return smsUrl != null && !smsUrl.toString().isBlank();
        }

        @Override
        public String toString() {
            return "Otp[smsUrl=" + smsUrl + ", smsAuthValue=****, validity=" + validity + "]";
        }
    }

    /**
     * FHIR record content.
     *
     * @param identifierSystem namespace for this hospital's identifiers (UHID, encounters, documents)
     * @param hospitalName     custodian organisation on every record
     */
    public record Fhir(@NotBlank String identifierSystem, String hospitalName) {

        public Fhir {
            if (hospitalName == null || hospitalName.isBlank()) {
                hospitalName = "Hospital name not configured (set SYNCRA_HOSPITAL_NAME)";
            }
        }
    }
}
