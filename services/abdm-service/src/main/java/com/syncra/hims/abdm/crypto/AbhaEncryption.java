package com.syncra.hims.abdm.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Encryption ABHA V3 requires for identifiers and OTPs sent to it:
 * {@code RSA/ECB/OAEPWithSHA-1AndMGF1Padding} with the public key ABHA publishes at
 * {@code /v3/profile/public/certificate}.
 */
public final class AbhaEncryption {

    public static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA1 = new OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);

    private AbhaEncryption() {
    }

    /** Parses ABHA's key: base64 X.509 SubjectPublicKeyInfo, with or without PEM armour. */
    public static PublicKey parsePublicKey(String published) {
        if (published == null || published.isBlank()) {
            throw new IllegalArgumentException("ABHA published no public key");
        }
        String base64 = published.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("ABHA public key is not a valid RSA key", e);
        }
    }

    public static String encrypt(PublicKey key, String value) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_SHA1);
            return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP encryption is unavailable", e);
        }
    }
}
