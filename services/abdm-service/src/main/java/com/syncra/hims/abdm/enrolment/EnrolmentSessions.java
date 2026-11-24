package com.syncra.hims.abdm.enrolment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.common.web.ApiException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * An ABHA enrolment in progress, kept in Redis for {@value #TTL_MINUTES} minutes (ABHA's own
 * transaction lifetime). Holds ABHA's transaction id and, once created, the short-lived ABHA user
 * token needed for the card. Never the Aadhaar number.
 */
@Component
public class EnrolmentSessions {

    static final int TTL_MINUTES = 30;
    private static final String PREFIX = "abdm:abha-enrolment:";

    public enum Stage { OTP_SENT, CREATED, MOBILE_OTP_SENT }

    /** @param aadhaarHint masked Aadhaar ({@code XXXX-XXXX-1234}) for display */
    public record Session(UUID id, String actor, String txnId, Stage stage, String aadhaarHint, String userToken,
                          String abhaNumber, UUID verificationId) {

        Session with(String newTxnId, Stage newStage) {
            return new Session(id, actor, newTxnId, newStage, aadhaarHint, userToken, abhaNumber, verificationId);
        }

        Session created(String newTxnId, String token, String number, UUID verification) {
            return new Session(id, actor, newTxnId, Stage.CREATED, aadhaarHint, token, number, verification);
        }

        @Override
        public String toString() {
            return "Session[id=" + id + ", stage=" + stage + ", userToken=****]";
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public EnrolmentSessions(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(Session session) {
        try {
            redis.opsForValue().set(PREFIX + session.id(), json.writeValueAsString(session), Duration.ofMinutes(TTL_MINUTES));
        } catch (DataAccessException | JsonProcessingException e) {
            throw unavailable();
        }
    }

    public Optional<Session> find(UUID id) {
        try {
            String value = redis.opsForValue().get(PREFIX + id);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, Session.class));
        } catch (DataAccessException | JsonProcessingException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENROLMENT_UNAVAILABLE",
                "ABHA creation is temporarily unavailable; please retry");
    }
}
