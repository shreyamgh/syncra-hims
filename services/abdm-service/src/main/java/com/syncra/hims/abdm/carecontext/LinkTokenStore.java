package com.syncra.hims.abdm.carecontext;

import com.syncra.hims.abdm.config.AbdmProperties;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Link tokens (X-LINK-TOKEN) issued by ABDM per patient, kept in Redis with a TTL so a run
 * of care contexts can be linked without a token round trip each time. A Redis outage only
 * costs an extra token request: failures are treated as "no token".
 */
@Component
public class LinkTokenStore {

    private static final Logger log = LoggerFactory.getLogger(LinkTokenStore.class);
    private static final String PREFIX = "abdm:link-token:";

    private final StringRedisTemplate redis;
    private final AbdmProperties properties;

    public LinkTokenStore(StringRedisTemplate redis, AbdmProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public Optional<String> get(UUID patientId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + patientId));
        } catch (DataAccessException e) {
            log.warn("Link token cache unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(UUID patientId, String token) {
        try {
            redis.opsForValue().set(PREFIX + patientId, token, properties.linking().linkTokenTtl());
        } catch (DataAccessException e) {
            log.warn("Link token cache unavailable: {}", e.getClass().getSimpleName());
        }
    }

    public void evict(UUID patientId) {
        try {
            redis.delete(PREFIX + patientId);
        } catch (DataAccessException e) {
            log.warn("Link token cache unavailable: {}", e.getClass().getSimpleName());
        }
    }
}
