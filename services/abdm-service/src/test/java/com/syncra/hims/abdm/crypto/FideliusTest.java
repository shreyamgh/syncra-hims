package com.syncra.hims.abdm.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncra.hims.abdm.crypto.Fidelius.KeyMaterial;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Interoperability with ABDM's reference implementation. The vectors are the worked
 * example in the fidelius-cli README (github.com/mgrmtech/fidelius-cli): if these pass,
 * an HIU using the reference tool can decrypt what this HIP sends, and vice versa.
 */
class FideliusTest {

    // "Requester" (HIU) key material from the README
    static final String REQUESTER_PRIVATE = "DMxHPri8d7IT23KgLk281zZenMfVHSdeamq0RhwlIBk=";
    static final String REQUESTER_PUBLIC = "BAheD5rUqTy4V5xR4/6HWmYpopu5CO+KO8BECS0udNqUTSNo91TIqIIy1A4Vh+F94c+n9vAcwXU2bGcfsI5f69Y=";
    static final String REQUESTER_X509 = "MIIBMTCB6gYHKoZIzj0CATCB3gIBATArBgcqhkjOPQEBAiB/////////////////////////////////////////7TBEBCAqqqqq"
            + "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqYSRShRAQge0Je0Je0Je0Je0Je0Je0Je0Je0Je0Je0JgtenHcQyGQEQQQqqqqqqqqqqqqq"
            + "qqqqqqqqqqqqqqqqqqqqqqqqqq0kWiCuGaG4oIa04B7dLHdI0UySPU1+bXxhsinpxaJ+ztPZAiAQAAAAAAAAAAAAAAAAAAAAFN75"
            + "3qL3nNZYEmMaXPXT7QIBCANCAAQIXg+a1Kk8uFecUeP+h1pmKaKbuQjvijvARAktLnTalE0jaPdUyKiCMtQOFYfhfeHPp/bwHMF1"
            + "NmxnH7COX+vW";
    static final String REQUESTER_NONCE = "6uj1RdDUbcpI3lVMZvijkMC8Te20O4Bcyz0SyivX8Eg=";

    // "Sender" (HIP) key material from the README
    static final String SENDER_PRIVATE = "AYhVZpbVeX4KS5Qm/W0+9Ye2q3rnVVGmqRICmseWni4=";
    static final String SENDER_PUBLIC = "BABVt+mpRLMXiQpIfEq6bj8hlXsdtXIxLsspmMgLNI1SR5mHgDVbjHO2A+U4QlMddGzqyEidzm1AkhtSxSO2Ahg=";
    static final String SENDER_X509 = "MIIBMTCB6gYHKoZIzj0CATCB3gIBATArBgcqhkjOPQEBAiB/////////////////////////////////////////7TBEBCAqqqqq"
            + "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqYSRShRAQge0Je0Je0Je0Je0Je0Je0Je0Je0Je0Je0JgtenHcQyGQEQQQqqqqqqqqqqqqq"
            + "qqqqqqqqqqqqqqqqqqqqqqqqqq0kWiCuGaG4oIa04B7dLHdI0UySPU1+bXxhsinpxaJ+ztPZAiAQAAAAAAAAAAAAAAAAAAAAFN75"
            + "3qL3nNZYEmMaXPXT7QIBCANCAAQAVbfpqUSzF4kKSHxKum4/IZV7HbVyMS7LKZjICzSNUkeZh4A1W4xztgPlOEJTHXRs6shInc5t"
            + "QJIbUsUjtgIY";
    static final String SENDER_NONCE = "lmXgblZwotx+DfBgKJF0lZXtAXgBEYr5khh79Zytr2Y=";

    static final String PLAINTEXT = "Wormtail should never have been Potter cottage's secret keeper.";
    static final String CIPHERTEXT = "pzMvVZNNVtJzqPkkxcCbBUWgDEBy/mBXIeT2dJWI16ZAQnnXUb9lI+S4k8XK6mgZSKKSRIHkcNvJpllnBg548wUgavBa0vCRRwdL6kY6Yw==";

    @Test
    void encryptionMatchesTheReferenceImplementation() {
        assertThat(Fidelius.encrypt(PLAINTEXT, SENDER_NONCE, REQUESTER_NONCE, SENDER_PRIVATE, REQUESTER_PUBLIC))
                .isEqualTo(CIPHERTEXT);
    }

    @Test
    void x509AndRawPublicKeysAreEquivalent() {
        assertThat(Fidelius.encrypt(PLAINTEXT, SENDER_NONCE, REQUESTER_NONCE, SENDER_PRIVATE, REQUESTER_X509))
                .isEqualTo(CIPHERTEXT);
    }

    @Test
    void decryptionMatchesTheReferenceImplementation() {
        assertThat(Fidelius.decrypt(CIPHERTEXT, REQUESTER_NONCE, SENDER_NONCE, REQUESTER_PRIVATE, SENDER_PUBLIC))
                .isEqualTo(PLAINTEXT);
        assertThat(Fidelius.decrypt(CIPHERTEXT, REQUESTER_NONCE, SENDER_NONCE, REQUESTER_PRIVATE, SENDER_X509))
                .isEqualTo(PLAINTEXT);
    }

    @Test
    void generatedKeyMaterialHasTheReferenceShape() {
        KeyMaterial keys = Fidelius.generateKeyMaterial();
        assertThat(keys.publicKey()).hasSize(88);
        assertThat(Base64.getDecoder().decode(keys.publicKey())[0]).isEqualTo((byte) 0x04);
        // Explicit curve parameters, as the reference tool encodes them
        assertThat(keys.x509PublicKey()).hasSize(REQUESTER_X509.length()).startsWith("MIIBMTCB6gYHKoZIzj0CAT");
        assertThat(Base64.getDecoder().decode(keys.nonce())).hasSize(32);
        assertThat(Fidelius.isUsablePublicKey(keys.x509PublicKey())).isTrue();
        assertThat(Fidelius.isUsableNonce(keys.nonce())).isTrue();
        assertThat(keys.toString()).doesNotContain(keys.privateKey());
    }

    @Test
    void roundTripWithFreshKeysAndUnicodeContent() {
        KeyMaterial hip = Fidelius.generateKeyMaterial();
        KeyMaterial hiu = Fidelius.generateKeyMaterial();
        String bundle = "{\"resourceType\":\"Bundle\",\"note\":\"ज्वर — fever 3 दिन\"}";
        String encrypted = Fidelius.encrypt(bundle, hip.nonce(), hiu.nonce(), hip.privateKey(), hiu.x509PublicKey());
        assertThat(Fidelius.decrypt(encrypted, hiu.nonce(), hip.nonce(), hiu.privateKey(), hip.x509PublicKey()))
                .isEqualTo(bundle);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] bytes = Base64.getDecoder().decode(CIPHERTEXT);
        bytes[5] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> Fidelius.decrypt(tampered, REQUESTER_NONCE, SENDER_NONCE, REQUESTER_PRIVATE, SENDER_PUBLIC))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authentication");
    }

    @Test
    void rejectsKeysAndNoncesThatAreNotFidelius() {
        assertThat(Fidelius.isUsablePublicKey("not-a-key")).isFalse();
        assertThat(Fidelius.isUsablePublicKey(null)).isFalse();
        assertThat(Fidelius.isUsableNonce(Base64.getEncoder().encodeToString(new byte[16]))).isFalse();
    }
}
