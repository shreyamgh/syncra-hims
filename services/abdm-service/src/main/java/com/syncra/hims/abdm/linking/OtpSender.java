package com.syncra.hims.abdm.linking;

import com.syncra.hims.abdm.config.AbdmProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends the linking OTP by SMS through the hospital's provider: a POST of
 * {@code {to, message, senderId, templateId, variables}} with the configured credential header.
 * Indian SMS needs a DLT-registered sender and template; providers whose API differs need a thin
 * adapter in front. Neither the OTP nor the message is ever logged.
 */
@Component
public class OtpSender {

    /** The OTP could not be handed to the SMS provider. */
    public static class DeliveryFailed extends RuntimeException {
        DeliveryFailed(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OtpSender.class);

    private final AbdmProperties properties;
    private final RestClient http;

    public OtpSender(AbdmProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1) // see AbdmClient
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return properties.otp().smsConfigured();
    }

    /** @param mobile 10-digit Indian mobile number */
    public void send(String mobile, String otp) {
        AbdmProperties.Otp settings = properties.otp();
        if (!settings.smsConfigured()) {
            throw new DeliveryFailed("OTP delivery is not configured");
        }
        String minutes = String.valueOf(settings.validity().toMinutes());
        String hospital = properties.fhir().hospitalName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", "91" + mobile);
        body.put("message", settings.message().replace("{otp}", otp).replace("{hospital}", hospital)
                .replace("{minutes}", minutes));
        if (settings.smsSenderId() != null && !settings.smsSenderId().isBlank()) {
            body.put("senderId", settings.smsSenderId());
        }
        if (settings.smsTemplateId() != null && !settings.smsTemplateId().isBlank()) {
            body.put("templateId", settings.smsTemplateId());
        }
        body.put("variables", Map.of("otp", otp, "hospital", hospital, "minutes", minutes));
        try {
            http.post().uri(settings.smsUrl()).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (settings.smsAuthValue() != null && !settings.smsAuthValue().isBlank()) {
                            h.set(settings.smsAuthHeader(), settings.smsAuthValue());
                        }
                    })
                    .body(body).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("SMS provider did not accept the OTP message: {}", e.getClass().getSimpleName());
            throw new DeliveryFailed("The SMS provider did not accept the message");
        }
    }
}
