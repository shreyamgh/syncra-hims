package com.syncra.hims.patient.emr.imaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Read-only access to Orthanc's REST API (change log, study, series and statistics). */
@Component
public class PacsClient {

    private final RestClient http;

    public PacsClient(PacsProperties properties, RestClient.Builder builder) {
        // HTTP/1.1 only: on a plain-http URL the JDK client otherwise asks to upgrade to HTTP/2 (h2c), and
        // servers that do not support it can drop the request body (uvicorn does: the CDSS saw no body).
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(20));
        RestClient.Builder configured = builder.requestFactory(factory);
        if (properties.enabled()) {
            configured = configured.baseUrl(properties.url().toString());
        }
        if (properties.username() != null && !properties.username().isBlank()) {
            String basic = Base64.getEncoder().encodeToString(
                    (properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8));
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        }
        this.http = configured.build();
    }

    /** {@code {Changes:[{ChangeType, ID, Seq}], Done, Last}} */
    public JsonNode changes(long since, int limit) {
        return get("/changes?since=" + since + "&limit=" + limit);
    }

    public JsonNode study(String orthancId) {
        return get("/studies/" + orthancId);
    }

    public JsonNode series(String orthancId) {
        return get("/studies/" + orthancId + "/series");
    }

    public JsonNode statistics(String orthancId) {
        return get("/studies/" + orthancId + "/statistics");
    }

    private JsonNode get(String path) {
        return http.get().uri(path).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
    }
}
