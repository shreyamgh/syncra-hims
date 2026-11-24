package com.syncra.hims.abdm.callback;

import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.GatewayMessageLog;
import com.syncra.hims.abdm.gateway.GatewayMessageLog.Outcome;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends the asynchronous {@code on-*} reply to an ABDM callback, quoting its REQUEST-ID as
 * {@code response.requestId}, and records whether the callback is now answered. An unanswered
 * callback is processed again when ABDM retries it.
 */
@Component
public class CallbackAck {

    /** Error codes this HIP reports to ABDM. */
    public static final int INVALID_REQUEST = 1400;
    public static final int INVALID_OTP = 1401;
    public static final int NOT_FOUND = 1404;
    public static final int AMBIGUOUS = 1409;
    public static final int TOO_MANY_REQUESTS = 1429;
    public static final int INTERNAL_ERROR = 1500;
    public static final int UNAVAILABLE = 1503;

    private final AbdmClient abdm;
    private final GatewayMessageLog messages;

    public CallbackAck(AbdmClient abdm, GatewayMessageLog messages) {
        this.abdm = abdm;
        this.messages = messages;
    }

    public static Map<String, Object> error(int code, String message) {
        return Map.of("code", code, "message", message);
    }

    /** @return true when ABDM accepted the reply */
    public boolean send(String path, UUID inboundRequestId, Map<String, Object> body, UUID transactionId) {
        Map<String, Object> reply = new LinkedHashMap<>(body);
        reply.put("response", Map.of("requestId", inboundRequestId.toString()));
        try {
            abdm.gatewayPost(path, reply, abdm.asHip(Call.plain().withTransaction(transactionId)));
            messages.inboundOutcome(inboundRequestId, Outcome.ACKED, null, null);
            return true;
        } catch (AbdmCallException e) {
            messages.inboundOutcome(inboundRequestId, Outcome.ERROR,
                    e.abdmCode() != null ? e.abdmCode() : e.code(), e.getMessage());
            return false;
        }
    }
}
