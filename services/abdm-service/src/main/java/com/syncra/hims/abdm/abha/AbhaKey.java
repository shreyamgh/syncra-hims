package com.syncra.hims.abdm.abha;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncra.hims.abdm.crypto.AbhaEncryption;
import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.gateway.AbdmClient;
import com.syncra.hims.abdm.gateway.AbdmClient.Call;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import java.security.PublicKey;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * ABHA's published RSA key, used to encrypt identifiers (ABHA number, Aadhaar, mobile) and OTPs
 * before they leave this service. Cached; fetched again once if ABHA rejects an encryption
 * (it rotates the key, and a stale one shows up as a 400).
 */
@Component
public class AbhaKey {

    private final AbdmClient client;
    private volatile PublicKey cached;

    public AbhaKey(AbdmClient client) {
        this.client = client;
    }

    public <T> T withEncryption(Function<PublicKey, T> call) {
        PublicKey key = current();
        try {
            return call.apply(key);
        } catch (AbdmCallException e) {
            if (e.upstreamStatus() == 400 && cached == key) {
                cached = null;
                PublicKey fresh = current();
                if (!fresh.equals(key)) {
                    return call.apply(fresh);
                }
            }
            throw e;
        }
    }

    private PublicKey current() {
        PublicKey key = cached;
        if (key == null) {
            JsonNode answer = client.abhaGet(AbdmPaths.ABHA_PUBLIC_CERTIFICATE, Call.plain()).body();
            JsonNode published = answer == null ? null : answer.get("publicKey");
            try {
                key = AbhaEncryption.parsePublicKey(published == null || published.isNull() ? null : published.asText());
            } catch (IllegalArgumentException e) {
                throw AbdmCallException.invalidResponse("ABHA public key");
            }
            cached = key;
        }
        return key;
    }
}
