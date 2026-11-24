package com.syncra.hims.patient.emr.cdss;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@code POST /api/v1/ai/cdss/check} on the AI service (snake_case JSON). */
@Component
public class CdssClient {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Line(String drugText, String dose, String frequency, Integer durationDays) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Allergy(String allergen, String drugClass) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Patient(Integer ageYears, String sex, BigDecimal weightKg, BigDecimal serumCreatinineMgDl) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(List<Line> prescriptions, List<Allergy> allergies, Patient patient) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Alert(String alertType, String severity, String ruleCode, String message, String management,
                        String source, List<Integer> lines, String allergen) {

        public boolean isSerious() {
            return "CONTRAINDICATED".equals(severity) || "MAJOR".equals(severity);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(String kbVersion, boolean kbReviewed, List<Alert> alerts, List<String> notChecked) {
    }

    private final RestClient http;

    public CdssClient(CdssProperties properties, RestClient.Builder builder) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1) // see PacsClient
                        .connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        RestClient.Builder configured = builder.requestFactory(factory);
        if (properties.configured()) {
            configured = configured.baseUrl(properties.url().toString());
        }
        this.http = configured.build();
    }

    public Response check(Request request) {
        return http.post().uri("/api/v1/ai/cdss/check").contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(Response.class);
    }
}
