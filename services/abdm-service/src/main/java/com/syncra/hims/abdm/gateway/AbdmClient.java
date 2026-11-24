package com.syncra.hims.abdm.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.gateway.GatewayMessageLog.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP client for ABDM: the HIE-CM gateway, the ABHA service and HIU data-push URLs.
 *
 * <ul>
 *   <li>Obtains and caches the gateway session token (client credentials), refreshing it a
 *       minute before expiry and once more if ABDM answers 401.</li>
 *   <li>Adds the headers every V3 call needs: {@code REQUEST-ID}, {@code TIMESTAMP},
 *       {@code X-CM-ID} and {@code Authorization}.</li>
 *   <li>Logs each call to {@code abdm.gateway_message} (path and status, never the body).</li>
 *   <li>Turns failures into {@link AbdmCallException}; redirects are never followed.</li>
 * </ul>
 */
@Component
public class AbdmClient {

    private static final Logger log = LoggerFactory.getLogger(AbdmClient.class);
    private static final Duration SESSION_REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    /**
     * What a call is about, for the traffic log and for matching ABDM's asynchronous reply.
     *
     * @param requestId     REQUEST-ID to send (a fresh one when null)
     * @param patientId     patient the call concerns, if any
     * @param transactionId ABDM transaction the call belongs to, if any
     * @param headers       extra headers ({@code X-HIP-ID}, {@code X-LINK-TOKEN}, ...)
     */
    public record Call(UUID requestId, UUID patientId, UUID transactionId, Map<String, String> headers) {

        public static Call plain() {
            return new Call(null, null, null, Map.of());
        }

        public static Call forPatient(UUID patientId) {
            return new Call(null, patientId, null, Map.of());
        }

        public Call withHeader(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            copy.put(name, value);
            return new Call(requestId, patientId, transactionId, copy);
        }

        public Call withTransaction(UUID transaction) {
            return new Call(requestId, patientId, transaction, headers);
        }
    }

    /**
     * A completed call: the REQUEST-ID it was sent with and ABDM's (possibly empty) answer;
     * {@code raw} and {@code contentType} for non-JSON answers such as the ABHA card image.
     */
    public record Response(UUID requestId, int status, JsonNode body, byte[] raw, String contentType) {
    }

    private record Session(String accessToken, Instant refreshAfter) {
    }

    private final AbdmProperties properties;
    private final RestClient http;
    private final GatewayMessageLog messages;
    private final ObjectMapper json;
    private final Clock clock;
    private final ReentrantLock sessionLock = new ReentrantLock();
    private volatile Session session;

    public AbdmClient(AbdmProperties properties, RestClient.Builder builder, GatewayMessageLog messages,
                      ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
        // HTTP/1.1: avoids the JDK client's cleartext HTTP/2 upgrade, which some servers mishandle
        // (dropped request bodies) when the gateway URL is plain http, as with local fakes.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.gateway().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.gateway().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    // ------------------------------------------------------------------ gateway (HIE-CM)

    /** POSTs to the HIE-CM gateway; ABDM accepts (202) and replies later through a callback. */
    public Response gatewayPost(String path, Object body, Call call) {
        return exchange(properties.gateway().baseUrl(), path, HttpMethod.POST, body, call, true, path, true);
    }

    /** Headers identifying this facility on HIP calls. */
    public Call asHip(Call call) {
        return call.withHeader(AbdmHeaders.HIP_ID, properties.hipId());
    }

    /** Headers identifying this facility on HIU calls. */
    public Call asHiu(Call call) {
        return call.withHeader(AbdmHeaders.HIU_ID, properties.hipId());
    }

    // ------------------------------------------------------------------ ABHA service

    public Response abhaPost(String path, Object body, Call call) {
        return exchange(properties.abha().baseUrl(), path, HttpMethod.POST, body, call, true, "abha:" + path, false);
    }

    public Response abhaGet(String path, Call call) {
        return exchange(properties.abha().baseUrl(), path, HttpMethod.GET, null, call, true, "abha:" + path, false);
    }

    // ------------------------------------------------------------------ HIU data push

    /**
     * POSTs encrypted records to the HIU's data-push URL (taken from a gateway-signed request).
     * The session token is attached only when {@code syncra.abdm.data-push.send-session-token} is on.
     */
    public Response pushToHiu(URI dataPushUrl, Object body, Call call) {
        boolean withToken = properties.dataPush().sendSessionToken();
        return exchange(dataPushUrl, "", HttpMethod.POST, body, call, withToken, "hiu:" + dataPushUrl.getHost(), false);
    }

    // ------------------------------------------------------------------ internals

    /**
     * @param retryOnUnauthorised retry once with a fresh session token after a 401. Only for gateway
     *                            calls: an ABHA 401 can mean a wrong OTP, and OTP attempts are limited.
     */
    private Response exchange(URI base, String path, HttpMethod method, Object body, Call call,
                              boolean authenticated, String logPath, boolean retryOnUnauthorised) {
        if (!properties.enabled()) {
            throw AbdmCallException.disabled();
        }
        UUID requestId = call.requestId() != null ? call.requestId() : UUID.randomUUID();
        try {
            Response response = send(base, path, method, body, call, requestId, authenticated);
            messages.outbound(requestId, logPath, call.patientId(), call.transactionId(), response.status(),
                    Outcome.SENT, null, null);
            return response;
        } catch (AbdmCallException e) {
            if (e.isUnauthorised() && authenticated && retryOnUnauthorised) {
                // Session revoked or expired early: one retry with a fresh token, same REQUEST-ID.
                session = null;
                try {
                    Response response = send(base, path, method, body, call, requestId, true);
                    messages.outbound(requestId, logPath, call.patientId(), call.transactionId(), response.status(),
                            Outcome.SENT, null, null);
                    return response;
                } catch (AbdmCallException retry) {
                    e = retry;
                }
            }
            messages.outbound(requestId, logPath, call.patientId(), call.transactionId(),
                    e.upstreamStatus() == 0 ? null : e.upstreamStatus(), Outcome.ERROR,
                    e.abdmCode() != null ? e.abdmCode() : e.code(), e.getMessage());
            throw e;
        }
    }

    private Response send(URI base, String path, HttpMethod method, Object body, Call call, UUID requestId,
                          boolean authenticated) {
        String bearer = authenticated ? accessToken() : null;
        URI uri = path.isEmpty() ? base : UriComponentsBuilder.fromUri(base).path(path).build(true).toUri();
        Map<String, String> headers = new HashMap<>(call.headers());
        headers.put(AbdmHeaders.REQUEST_ID, requestId.toString());
        headers.put(AbdmHeaders.TIMESTAMP, timestamp());
        headers.putIfAbsent(AbdmHeaders.CM_ID, properties.cmId());
        try {
            RestClient.RequestBodySpec request = http.method(method).uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        headers.forEach(h::set);
                        if (bearer != null) {
                            h.setBearerAuth(bearer);
                        }
                    });
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, resp) -> {
                int status = resp.getStatusCode().value();
                byte[] raw;
                try (InputStream stream = resp.getBody()) {
                    raw = stream.readAllBytes();
                }
                JsonNode answer = parse(raw);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    MediaType type = resp.getHeaders().getContentType();
                    return new Response(requestId, status, answer, raw, type == null ? null : type.toString());
                }
                String code = text(answer, "error", "code");
                String message = text(answer, "error", "message");
                if (code == null) {
                    code = text(answer, "code");
                    message = message != null ? message : text(answer, "message");
                }
                if (status == 401 || status == 403) {
                    throw AbdmCallException.rejected(status, code, message);
                }
                throw resp.getStatusCode().is4xxClientError()
                        ? AbdmCallException.rejected(status, code, message)
                        : AbdmCallException.failed(status, code);
            });
        } catch (ResourceAccessException e) {
            log.warn("ABDM call to {} failed: {}", uri.getHost(), e.getClass().getSimpleName());
            throw AbdmCallException.unreachable();
        }
    }

    /** Gateway session token, fetched with the client credentials and cached until shortly before expiry. */
    String accessToken() {
        Session current = session;
        if (current != null && Instant.now(clock).isBefore(current.refreshAfter())) {
            return current.accessToken();
        }
        sessionLock.lock();
        try {
            current = session;
            if (current != null && Instant.now(clock).isBefore(current.refreshAfter())) {
                return current.accessToken();
            }
            Map<String, String> credentials = Map.of(
                    "clientId", properties.gateway().clientId(),
                    "clientSecret", properties.gateway().clientSecret(),
                    "grantType", "client_credentials");
            UUID requestId = UUID.randomUUID();
            Response response;
            try {
                response = send(properties.gateway().baseUrl(), AbdmPaths.SESSIONS, HttpMethod.POST, credentials,
                        Call.plain(), requestId, false);
            } catch (AbdmCallException e) {
                messages.outbound(requestId, AbdmPaths.SESSIONS, null, null,
                        e.upstreamStatus() == 0 ? null : e.upstreamStatus(), Outcome.ERROR, e.code(), e.getMessage());
                throw e.upstreamStatus() == 401 || e.upstreamStatus() == 400
                        ? AbdmCallException.credentialsRejected(e.upstreamStatus())
                        : e;
            }
            String token = text(response.body(), "accessToken");
            if (token == null) {
                throw AbdmCallException.invalidResponse("no accessToken in session response");
            }
            long expiresIn = response.body().path("expiresIn").asLong(600);
            Instant refreshAfter = Instant.now(clock).plusSeconds(expiresIn).minus(SESSION_REFRESH_MARGIN);
            session = new Session(token, refreshAfter);
            return token;
        } finally {
            sessionLock.unlock();
        }
    }

    private String timestamp() {
        return TIMESTAMP.format(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes.length == 0 || bytes[0] != '{' && bytes[0] != '[') {
            return json.missingNode();
        }
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            return json.missingNode();
        }
    }

    private static String text(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            if (current == null) {
                return null;
            }
            current = current.get(field);
        }
        return current == null || current.isNull() || current.isMissingNode() ? null : current.asText();
    }
}
