package com.syncra.hims.common.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A business-rule failure with an HTTP status, a stable machine-readable
 * {@code code} for clients, and a message safe to show to staff (no PHI).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }

    public static ApiException notFound(String what, Object key) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + key + " was not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
