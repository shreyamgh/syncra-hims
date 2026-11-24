package com.syncra.hims.patient.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * One local HTTP server standing in for the Orthanc PACS (change log, studies; basic auth) and
 * the AI service's CDSS endpoint (scripted alerts, every request recorded).
 */
public final class FakeUpstreams {

    public static final String PACS_USER = "pacs";
    public static final String PACS_PASSWORD = "pacs-secret";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final List<Map<String, Object>> changes = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> studies = new LinkedHashMap<>();
    private final List<JsonNode> cdssRequests = new CopyOnWriteArrayList<>();
    private volatile List<Map<String, Object>> cdssAlerts = List.of();
    private volatile boolean cdssDown;

    public FakeUpstreams() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ CDSS

    public void cdssAlerts(List<Map<String, Object>> alerts) {
        cdssAlerts = alerts;
    }

    public void cdssDown(boolean down) {
        cdssDown = down;
    }

    public JsonNode lastCdssRequest() {
        return cdssRequests.isEmpty() ? null : cdssRequests.get(cdssRequests.size() - 1);
    }

    public static Map<String, Object> alert(String type, String severity, String code, String message, int... lines) {
        List<Integer> list = new ArrayList<>();
        for (int line : lines) {
            list.add(line);
        }
        return Map.of("alert_type", type, "severity", severity, "rule_code", code, "message", message, "lines", list);
    }

    // ------------------------------------------------------------------ PACS

    /** A study becomes stable in Orthanc. */
    public synchronized void stableStudy(String orthancId, String studyUid, String patientId, String accession,
                                         String modality, String description, int series, int instances) {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("StudyInstanceUID", studyUid);
        tags.put("StudyDate", "20261120");
        tags.put("StudyDescription", description);
        if (accession != null) {
            tags.put("AccessionNumber", accession);
        }
        studies.put(orthancId, Map.of("ID", orthancId, "MainDicomTags", tags,
                "PatientMainDicomTags", Map.of("PatientID", patientId), "modality", modality,
                "series", series, "instances", instances));
        changes.add(Map.of("ChangeType", "NewInstance", "ID", "instance-x", "Seq", changes.size() + 1));
        changes.add(Map.of("ChangeType", "StableStudy", "ID", orthancId, "Seq", changes.size() + 1, "ResourceType", "Study"));
    }

    // ------------------------------------------------------------------ server

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] raw = exchange.getRequestBody().readAllBytes();
        try {
            if (path.equals("/api/v1/ai/cdss/check")) {
                // Like the real AI service (uvicorn/h11): a request that asks to upgrade to cleartext
                // HTTP/2 arrives without its body, which FastAPI rejects as 422 "body: Field required".
                if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                    respond(exchange, 422, Map.of("detail", List.of(Map.of("type", "missing", "loc", List.of("body"),
                            "msg", "Field required"))));
                    return;
                }
                cdssRequests.add(json.readTree(raw));
                if (cdssDown) {
                    respond(exchange, 503, Map.of("detail", "down"));
                    return;
                }
                respond(exchange, 200, Map.of("kb_version", "test-kb-1", "kb_reviewed", false, "alerts", cdssAlerts,
                        "recognised", List.of(), "not_checked", List.of()));
                return;
            }
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (PACS_USER + ":" + PACS_PASSWORD).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, Map.of());
                return;
            }
            if (path.equals("/changes")) {
                long since = Long.parseLong(query(exchange, "since"));
                int limit = Integer.parseInt(query(exchange, "limit"));
                List<Map<String, Object>> page = changes.stream()
                        .filter(c -> ((Number) c.get("Seq")).longValue() > since).limit(limit).toList();
                long last = page.isEmpty() ? since : ((Number) page.get(page.size() - 1).get("Seq")).longValue();
                respond(exchange, 200, Map.of("Changes", page, "Done", last >= changes.size(), "Last", last));
                return;
            }
            String[] parts = path.split("/");
            if (parts.length >= 3 && parts[1].equals("studies") && studies.containsKey(parts[2])) {
                Map<String, Object> study = studies.get(parts[2]);
                if (parts.length == 3) {
                    respond(exchange, 200, Map.of("ID", study.get("ID"), "MainDicomTags", study.get("MainDicomTags"),
                            "PatientMainDicomTags", study.get("PatientMainDicomTags")));
                } else if (parts[3].equals("series")) {
                    respond(exchange, 200, List.of(Map.of("MainDicomTags", Map.of("Modality", study.get("modality")))));
                } else if (parts[3].equals("statistics")) {
                    respond(exchange, 200, Map.of("CountSeries", study.get("series"), "CountInstances", study.get("instances")));
                } else {
                    respond(exchange, 404, Map.of());
                }
                return;
            }
            respond(exchange, 404, Map.of());
        } catch (RuntimeException e) {
            respond(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static String query(HttpExchange exchange, String name) {
        for (String pair : exchange.getRequestURI().getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return kv[1];
            }
        }
        return "0";
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
