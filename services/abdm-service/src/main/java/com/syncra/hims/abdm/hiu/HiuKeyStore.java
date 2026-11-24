package com.syncra.hims.abdm.hiu;

import com.syncra.hims.abdm.crypto.Fidelius.KeyMaterial;
import com.syncra.hims.common.config.HospitalTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The one-time ECDH key of each data request, held in this process's memory only: never in the
 * database, Redis (which persists to disk) or logs. A restart between the request and the push
 * loses the key; that transfer fails and the doctor asks again.
 */
@Component
public class HiuKeyStore {

    private record Held(KeyMaterial key, Instant expiresAt) {
    }

    private final Map<UUID, Held> keys = new ConcurrentHashMap<>();
    private final HospitalTime time;

    public HiuKeyStore(HospitalTime time) {
        this.time = time;
    }

    public void put(UUID dataRequestId, KeyMaterial key, Instant expiresAt) {
        keys.put(dataRequestId, new Held(key, expiresAt));
    }

    public Optional<KeyMaterial> get(UUID dataRequestId) {
        Held held = keys.get(dataRequestId);
        if (held == null || !held.expiresAt().isAfter(time.now())) {
            return Optional.empty();
        }
        return Optional.of(held.key());
    }

    public void forget(UUID dataRequestId) {
        keys.remove(dataRequestId);
    }

    @Scheduled(fixedDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void evictExpired() {
        Instant now = time.now();
        keys.values().removeIf(h -> !h.expiresAt().isAfter(now));
    }
}
