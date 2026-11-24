package com.syncra.hims.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes to the append-only {@code audit.event_log}.
 *
 * <p>Writes are recorded inside the business transaction (an audit row exists
 * if and only if the change committed). Reads are recorded by controllers after
 * the read transaction, in a transaction of their own. {@code details} must
 * hold reasons/field names only, never PHI values.
 */
public class AuditService {

    public enum Action { CREATE, READ, UPDATE, DELETE, PRINT, EXPORT, SHARE }

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public AuditService(JdbcClient jdbc, CurrentUser currentUser, ObjectMapper objectMapper, String serviceName) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    @Transactional
    public void record(Action action, String entityType, Object entityId, UUID patientId, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit.event_log
                    (actor, actor_role, action, entity_type, entity_id, patient_id, request_id, client_ip, service, details)
                VALUES (:actor, :role, :action, :entityType, :entityId, :patientId, :requestId,
                        CAST(:clientIp AS inet), :service, CAST(:details AS jsonb))
                """)
                .param("actor", currentUser.subject())
                .param("role", currentUser.primaryRole().orElse(null))
                .param("action", action.name())
                .param("entityType", entityType)
                .param("entityId", entityId == null ? null : entityId.toString())
                .param("patientId", patientId)
                .param("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                .param("clientIp", clientIp())
                .param("service", serviceName)
                .param("details", toJson(details))
                .update();
    }

    public void record(Action action, String entityType, Object entityId, UUID patientId) {
        record(action, entityType, entityId, patientId, null);
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details must be JSON-serialisable", e);
        }
    }

    /** Original client address as forwarded by the gateway, else the TCP peer. */
    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String candidate = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return candidate != null && IP_LITERAL.matcher(candidate).matches() ? candidate : null;
    }
}
