package com.syncra.hims.abdm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * A stand-in for ABDM on one local HTTP server: the HIE-CM gateway (sessions, JWKS, the
 * asynchronous APIs, which answer 202), the ABHA service (RSA-encrypted OTP login and
 * profile) and an HIU data-push endpoint. Everything received is recorded for assertions,
 * and callbacks to the HIP can be signed with the gateway's key.
 */
public final class FakeAbdm implements AutoCloseable {

    public static final String CLIENT_ID = "SYNCRA_TEST_CLIENT";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String SESSION_TOKEN = "gateway-session-token-1";
    public static final String USER_TOKEN = "abha-user-token-1";
    public static final String VALID_OTP = "123456";

    /** One request ABDM (or the HIU) received from the HIP. */
    public record Received(String method, String path, Map<String, String> headers, JsonNode body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** The ABHA profile the fake returns for the ABHA number last logged in with. */
    public record Profile(String name, String gender, int yearOfBirth, String abhaAddress, String status) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final RSAKey gatewayKey;
    private final KeyPair abhaKeys;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Profile profile = new Profile("Ramesh Kulkarni", "M", 1975, "ramesh.k@sbx", "ACTIVE");
    private volatile String lastAbhaNumber;
    private volatile int hiuStatus = 202;
    private volatile int smsStatus = 202;
    /** Mobile UIDAI has on record for every test Aadhaar. */
    public static final String AADHAAR_MOBILE = "9876543210";
    private volatile String lastAadhaar;
    private volatile String lastEnrolmentMobile;
    private volatile String lastCreatedAbha;
    private volatile boolean nextEnrolmentExists;

    public FakeAbdm() {
        try {
            gatewayKey = new RSAKeyGenerator(2048).keyID("abdm-gateway-test").generate();
            KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
            rsa.initialize(2048);
            abhaKeys = rsa.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String gatewayUrl() {
        return origin() + "/api/hiecm";
    }

    public String abhaUrl() {
        return origin() + "/abha/api";
    }

    public String hiuPushUrl() {
        return origin() + "/hiu/data/push";
    }

    /** The hospital's SMS provider. */
    public String smsUrl() {
        return origin() + "/sms/send";
    }

    public void smsStatus(int status) {
        smsStatus = status;
    }

    /** The Aadhaar number ABHA last received (decrypted), to prove the HIP encrypted it correctly. */
    public String lastAadhaar() {
        return lastAadhaar;
    }

    public String lastEnrolmentMobile() {
        return lastEnrolmentMobile;
    }

    /** The next enrolment finds an ABHA that already exists for that Aadhaar. */
    public void nextEnrolmentExists(boolean value) {
        nextEnrolmentExists = value;
    }

    public void profile(Profile value) {
        profile = value;
    }

    public void hiuStatus(int status) {
        hiuStatus = status;
    }

    // ------------------------------------------------------------------ assertions

    /** The n-th (1-based) request to a path ending with {@code suffix}, waiting up to 10 s for it. */
    public Received await(String suffix, int nth) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            List<Received> matching = matching(suffix);
            if (matching.size() >= nth) {
                return matching.get(nth - 1);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("No request #" + nth + " to *" + suffix + " (got " + matching(suffix).size() + ")");
    }

    public Received await(String suffix) {
        return await(suffix, 1);
    }

    /**
     * The first request to a path that {@code which} accepts (e.g. one carrying this test's request
     * id). Use this rather than counting: a late reply from another test can land meanwhile.
     */
    public Received awaitMatching(String suffix, java.util.function.Predicate<Received> which) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            java.util.Optional<Received> found = matching(suffix).stream().filter(which).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("No matching request to *" + suffix);
    }

    /** How many requests to a path {@code which} accepts, after letting stragglers arrive. */
    public long countMatching(String suffix, java.util.function.Predicate<Received> which) throws InterruptedException {
        Thread.sleep(400);
        return matching(suffix).stream().filter(which).count();
    }

    public int count(String suffix) {
        return matching(suffix).size();
    }

    /** Waits briefly and confirms nothing more reached {@code suffix}. */
    public void assertNoMore(String suffix, int expected) throws InterruptedException {
        Thread.sleep(400);
        if (count(suffix) != expected) {
            throw new AssertionError("Expected " + expected + " request(s) to *" + suffix + ", got " + count(suffix));
        }
    }

    private List<Received> matching(String suffix) {
        List<Received> list = new ArrayList<>();
        for (Received r : received) {
            if (r.path().endsWith(suffix)) {
                list.add(r);
            }
        }
        return list;
    }

    /** Decrypts what the HIP encrypted with ABHA's public key. */
    public String decryptForAbha(String base64) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, abhaKeys.getPrivate(),
                    new OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new AssertionError("Not decryptable with the ABHA key", e);
        }
    }

    // ------------------------------------------------------------------ callback tokens

    /** A JWT as the ABDM gateway signs its calls to the HIP. */
    public String gatewayToken() {
        return sign(gatewayKey, Instant.now().plusSeconds(300));
    }

    public String expiredGatewayToken() {
        return sign(gatewayKey, Instant.now().minusSeconds(600));
    }

    /** Signed with a key ABDM does not publish. */
    public static String forgedToken() {
        try {
            return sign(new RSAKeyGenerator(2048).keyID("abdm-gateway-test").generate(), Instant.now().plusSeconds(300));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(RSAKey key, Instant expiry) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder().subject("gateway").issuer("https://dev.abdm.gov.in/gateway")
                            .issueTime(Date.from(expiry.minusSeconds(600))).expirationTime(Date.from(expiry))
                            .claim("clientId", "gateway").build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ server

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        JsonNode body = raw.length == 0 ? json.nullNode() : json.readTree(raw);
        received.add(new Received(exchange.getRequestMethod(), path, headers, body));
        try {
            route(exchange, path, headers, body);
        } catch (RuntimeException e) {
            respond(exchange, 500, Map.of("error", Map.of("code", "FAKE-500", "message", String.valueOf(e.getMessage()))));
        }
    }

    private void route(HttpExchange exchange, String path, Map<String, String> headers, JsonNode body) throws IOException {
        if (path.equals("/api/hiecm/gateway/v3/certs")) {
            respondRaw(exchange, 200, new JWKSet(gatewayKey.toPublicJWK()).toString());
            return;
        }
        if (path.equals("/api/hiecm/gateway/v3/sessions")) {
            boolean ok = CLIENT_ID.equals(body.path("clientId").asText())
                    && CLIENT_SECRET.equals(body.path("clientSecret").asText())
                    && "client_credentials".equals(body.path("grantType").asText());
            respond(exchange, ok ? 200 : 401, ok
                    ? Map.of("accessToken", SESSION_TOKEN, "expiresIn", 1200, "tokenType", "bearer")
                    : Map.of("error", Map.of("code", "ABDM-1000", "message", "Invalid client credentials")));
            return;
        }
        if (path.startsWith("/hiu/")) {
            respond(exchange, hiuStatus, Map.of());
            return;
        }
        if (path.startsWith("/sms/")) {
            boolean authorised = "Key sms-test-key".equals(headers.get("authorization"));
            respond(exchange, authorised ? smsStatus : 401, Map.of());
            return;
        }
        if (!("Bearer " + SESSION_TOKEN).equals(headers.get("authorization"))) {
            respond(exchange, 401, Map.of("error", Map.of("code", "ABDM-1001", "message", "Unauthorized")));
            return;
        }
        if (path.startsWith("/api/hiecm/")) {
            respond(exchange, 202, null);
            return;
        }
        switch (path) {
            case "/abha/api/v3/profile/public/certificate" -> respond(exchange, 200, Map.of(
                    "publicKey", Base64.getEncoder().encodeToString(abhaKeys.getPublic().getEncoded()),
                    "encryptionAlgorithm", "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"));
            case "/abha/api/v3/profile/login/request/otp" -> {
                lastAbhaNumber = decryptForAbha(body.path("loginId").asText());
                respond(exchange, 200, Map.of("txnId", "abha-txn-" + UUID.randomUUID(),
                        "message", "OTP sent to Aadhaar registered mobile number ending with ******3210"));
            }
            case "/abha/api/v3/profile/login/verify" -> {
                String otp = decryptForAbha(body.at("/authData/otp/otpValue").asText());
                if (!VALID_OTP.equals(otp)) {
                    respond(exchange, 401, Map.of("error", Map.of("code", "ABDM-1204", "message", "Invalid OTP value")));
                    return;
                }
                respond(exchange, 200, Map.of("txnId", body.at("/authData/otp/txnId").asText(), "authResult", "success",
                        "message", "OTP verified successfully", "token", USER_TOKEN, "expiresIn", 1800));
            }
            case "/abha/api/v3/enrollment/request/otp" -> {
                String loginId = decryptForAbha(body.path("loginId").asText());
                if ("aadhaar".equals(body.path("loginHint").asText())) {
                    lastAadhaar = loginId;
                } else {
                    lastEnrolmentMobile = loginId;
                }
                respond(exchange, 200, Map.of("txnId", "enrol-txn-" + UUID.randomUUID(),
                        "message", "OTP sent to mobile number ending with ******" + AADHAAR_MOBILE.substring(6)));
            }
            case "/abha/api/v3/enrollment/enrol/byAadhaar" -> {
                if (!VALID_OTP.equals(decryptForAbha(body.at("/authData/otp/otpValue").asText()))) {
                    respond(exchange, 400, Map.of("error", Map.of("code", "ABDM-1204", "message", "Invalid OTP value")));
                    return;
                }
                Profile p = profile;
                String mobile = body.at("/authData/otp/mobile").asText();
                lastCreatedAbha = "91" + String.format("%012d", (long) (Math.random() * 1_000_000_000_000L));
                ObjectNode account = json.createObjectNode();
                account.put("ABHANumber", lastCreatedAbha.substring(0, 2) + "-" + lastCreatedAbha.substring(2, 6) + "-"
                        + lastCreatedAbha.substring(6, 10) + "-" + lastCreatedAbha.substring(10));
                account.put("firstName", p.name().split(" ")[0]);
                account.put("lastName", p.name().substring(p.name().indexOf(' ') + 1));
                account.put("dob", "05-05-" + p.yearOfBirth());
                account.put("gender", p.gender());
                if (AADHAAR_MOBILE.equals(mobile)) {
                    account.put("mobile", mobile);
                } else {
                    account.putNull("mobile");
                }
                account.putArray("phrAddress");
                account.put("pinCode", "411005");
                account.put("stateName", "MAHARASHTRA");
                account.put("abhaStatus", "ACTIVE");
                account.put("photo", "/9j/4AAQSkZJRgABAQAAAQABAAD");
                ObjectNode answer = json.createObjectNode();
                answer.put("message", "Account created successfully");
                answer.put("txnId", body.at("/authData/otp/txnId").asText());
                answer.putObject("tokens").put("token", USER_TOKEN).put("expiresIn", 1800);
                answer.set("ABHAProfile", account);
                answer.put("isNew", !nextEnrolmentExists);
                nextEnrolmentExists = false;
                respondRaw(exchange, 200, json.writeValueAsString(answer));
            }
            case "/abha/api/v3/enrollment/auth/byAbdm" -> {
                boolean ok = VALID_OTP.equals(decryptForAbha(body.at("/authData/otp/otpValue").asText()));
                respond(exchange, ok ? 200 : 401, ok
                        ? Map.of("txnId", body.at("/authData/otp/txnId").asText(), "authResult", "success")
                        : Map.of("error", Map.of("code", "ABDM-1204", "message", "Invalid OTP value")));
            }
            case "/abha/api/v3/enrollment/enrol/suggestion" -> {
                if (headers.get("transaction_id") == null) {
                    respond(exchange, 400, Map.of("error", Map.of("code", "ABDM-1001", "message", "Transaction_Id missing")));
                    return;
                }
                respond(exchange, 200, Map.of("abhaAddressList", List.of("ramesh.kulkarni1975", "ramesh_1975"),
                        "txnId", headers.get("transaction_id")));
            }
            case "/abha/api/v3/enrollment/enrol/abha-address" -> respond(exchange, 200, Map.of(
                    "txnId", body.path("txnId").asText(), "healthIdNumber", String.valueOf(lastCreatedAbha),
                    "preferredAbhaAddress", body.path("abhaAddress").asText() + "@sbx"));
            case "/abha/api/v3/profile/account/abha-card" -> {
                if (!("Bearer " + USER_TOKEN).equals(headers.get("x-token"))) {
                    respond(exchange, 401, Map.of("error", Map.of("code", "ABDM-1002", "message", "Invalid X-token")));
                    return;
                }
                byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            }
            case "/abha/api/v3/profile/account" -> {
                if (!("Bearer " + USER_TOKEN).equals(headers.get("x-token"))) {
                    respond(exchange, 401, Map.of("error", Map.of("code", "ABDM-1002", "message", "Invalid X-token")));
                    return;
                }
                Profile p = profile;
                ObjectNode account = json.createObjectNode();
                account.put("ABHANumber", lastAbhaNumber);
                account.put("preferredAbhaAddress", p.abhaAddress());
                account.put("name", p.name());
                account.put("firstName", p.name().split(" ")[0]);
                account.put("lastName", p.name().contains(" ") ? p.name().substring(p.name().indexOf(' ') + 1) : "");
                account.put("gender", p.gender());
                account.put("yearOfBirth", String.valueOf(p.yearOfBirth()));
                account.put("monthOfBirth", "5");
                account.put("dayOfBirth", "5");
                account.put("mobile", "9876543210");
                account.put("stateName", "MAHARASHTRA");
                account.put("districtName", "PUNE");
                account.put("pincode", "411005");
                account.put("abhaStatus", p.status());
                account.put("profilePhoto", "/9j/4AAQSkZJRgABAQAAAQABAAD");
                respondRaw(exchange, 200, json.writeValueAsString(account));
            }
            default -> respond(exchange, 404, Map.of("error", Map.of("code", "FAKE-404", "message", "No such path")));
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        respondRaw(exchange, status, body == null ? "" : json.writeValueAsString(body));
    }

    private static void respondRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
