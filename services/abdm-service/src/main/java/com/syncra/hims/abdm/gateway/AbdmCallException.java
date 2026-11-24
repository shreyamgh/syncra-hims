package com.syncra.hims.abdm.gateway;

import com.syncra.hims.common.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A call to ABDM that did not succeed. Rendered to staff as an RFC 7807 problem:
 * 422 when ABDM rejected the request (wrong OTP, unknown ABHA), 502/503 when ABDM
 * failed or could not be reached, 503 when the integration is switched off.
 */
public class AbdmCallException extends ApiException {

    private final String abdmCode;
    private final int upstreamStatus;

    private AbdmCallException(HttpStatus status, String code, String message, String abdmCode, int upstreamStatus) {
        super(status, code, message, abdmCode == null ? Map.of() : Map.of("abdmCode", abdmCode));
        this.abdmCode = abdmCode;
        this.upstreamStatus = upstreamStatus;
    }

    public static AbdmCallException disabled() {
        return new AbdmCallException(HttpStatus.SERVICE_UNAVAILABLE, "ABDM_DISABLED",
                "ABDM integration is switched off (set SYNCRA_ABDM_ENABLED=true with sandbox or production credentials)",
                null, 0);
    }

    public static AbdmCallException rejected(int upstreamStatus, String abdmCode, String message) {
        String detail = message == null || message.isBlank() ? "ABDM rejected the request" : "ABDM: " + Redact.text(message);
        return new AbdmCallException(HttpStatus.UNPROCESSABLE_ENTITY, "ABDM_REJECTED", detail, abdmCode, upstreamStatus);
    }

    public static AbdmCallException failed(int upstreamStatus, String abdmCode) {
        return new AbdmCallException(HttpStatus.BAD_GATEWAY, "ABDM_ERROR",
                "ABDM could not process the request (HTTP " + upstreamStatus + "); please retry", abdmCode, upstreamStatus);
    }

    public static AbdmCallException credentialsRejected(int upstreamStatus) {
        return new AbdmCallException(HttpStatus.BAD_GATEWAY, "ABDM_CREDENTIALS_REJECTED",
                "ABDM refused this hospital's client credentials (check SYNCRA_ABDM_CLIENT_ID / _SECRET)",
                null, upstreamStatus);
    }

    public static AbdmCallException unreachable() {
        return new AbdmCallException(HttpStatus.SERVICE_UNAVAILABLE, "ABDM_UNREACHABLE",
                "ABDM could not be reached; please retry", null, 0);
    }

    public static AbdmCallException invalidResponse(String what) {
        return new AbdmCallException(HttpStatus.BAD_GATEWAY, "ABDM_INVALID_RESPONSE",
                "ABDM sent an unexpected response (" + what + ")", null, 0);
    }

    /** ABDM's own error code (for example {@code ABDM-1017}), when it sent one. */
    public String abdmCode() {
        return abdmCode;
    }

    /** HTTP status ABDM answered with; 0 when there was no answer. */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    /** True when ABDM no longer accepts our session token. */
    public boolean isUnauthorised() {
        return upstreamStatus == 401;
    }
}
