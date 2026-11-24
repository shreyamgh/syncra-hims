package com.syncra.hims.abdm.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;

/**
 * ABDM's health-information encryption ("Fidelius"), bit-compatible with the reference
 * implementation (github.com/mgrmtech/fidelius-cli):
 *
 * <ol>
 *   <li>ECDH on BouncyCastle's {@code curve25519} (short-Weierstrass form, not X25519) between
 *       the sender's private key and the requester's public key.</li>
 *   <li>XOR of the two 32-byte nonces: the first 20 bytes are the HKDF salt, the last 12 the IV.</li>
 *   <li>AES-256 key = HKDF-SHA256(ikm = shared secret, salt, no info).</li>
 *   <li>AES-GCM, 128-bit tag, no AAD; output is base64(ciphertext || tag).</li>
 * </ol>
 * Public keys travel as base64 X.509 (SubjectPublicKeyInfo); a base64 raw uncompressed point
 * (88 characters) is accepted too, as the reference tool does.
 *
 * <p>Each data transfer uses a fresh key pair and nonce that are never persisted.
 */
public final class Fidelius {

    public static final String CURVE = "Curve25519";
    public static final String ALGORITHM = "ECDH";
    /** Value of {@code dhPublicKey.parameters} in ABDM key material. */
    public static final String KEY_PARAMETERS = "Curve25519/32byte random key";

    private static final String BC_CURVE = "curve25519";
    private static final int NONCE_BYTES = 32;
    private static final int RAW_POINT_BASE64_LENGTH = 88;
    private static final int SALT_BYTES = 20;
    private static final int IV_BYTES = 12;
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;

    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final ECParameterSpec CURVE_SPEC;
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        X9ECParameters params = CustomNamedCurves.getByName(BC_CURVE);
        CURVE_SPEC = new ECParameterSpec(params.getCurve(), params.getG(), params.getN(), params.getH(), params.getSeed());
    }

    /**
     * One side's key material.
     *
     * @param privateKey    base64 of the private scalar (big-endian, two's complement) — keep in memory only
     * @param publicKey     base64 of the raw uncompressed point
     * @param x509PublicKey base64 X.509 SubjectPublicKeyInfo — what ABDM messages carry
     * @param nonce         base64 of 32 random bytes
     */
    public record KeyMaterial(String privateKey, String publicKey, String x509PublicKey, String nonce) {

        @Override
        public String toString() {
            return "KeyMaterial[privateKey=****, x509PublicKey=" + x509PublicKey + ", nonce=" + nonce + "]";
        }
    }

    private Fidelius() {
    }

    public static KeyMaterial generateKeyMaterial() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM, PROVIDER);
            generator.initialize(CURVE_SPEC, RANDOM);
            KeyPair pair = generator.generateKeyPair();
            ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
            ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            return new KeyMaterial(
                    base64(privateKey.getD().toByteArray()),
                    base64(publicKey.getQ().getEncoded(false)),
                    base64(publicKey.getEncoded()),
                    base64(nonce));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Curve25519 key generation is unavailable", e);
        }
    }

    /**
     * Encrypts for the requester (the HIU).
     *
     * @param plaintext          the FHIR bundle (UTF-8)
     * @param senderNonce        this HIP's nonce
     * @param requesterNonce     the HIU's nonce from the request
     * @param senderPrivateKey   this HIP's one-time private key
     * @param requesterPublicKey the HIU's public key from the request
     */
    public static String encrypt(String plaintext, String senderNonce, String requesterNonce,
                                 String senderPrivateKey, String requesterPublicKey) {
        byte[] key = aesKey(senderPrivateKey, requesterPublicKey, senderNonce, requesterNonce);
        byte[] iv = iv(senderNonce, requesterNonce);
        return base64(gcm(true, key, iv, plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    /** Decrypts what a sender encrypted for us (used by the simulated HIU in tests and for diagnostics). */
    public static String decrypt(String encrypted, String requesterNonce, String senderNonce,
                                 String requesterPrivateKey, String senderPublicKey) {
        byte[] key = aesKey(requesterPrivateKey, senderPublicKey, senderNonce, requesterNonce);
        byte[] iv = iv(senderNonce, requesterNonce);
        return new String(gcm(false, key, iv, Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
    }

    /** True when {@code value} is a base64 public key on Curve25519 that this class can use. */
    public static boolean isUsablePublicKey(String value) {
        try {
            publicKey(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when {@code value} is a base64 32-byte nonce. */
    public static boolean isUsableNonce(String value) {
        try {
            return value != null && Base64.getDecoder().decode(value).length == NONCE_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ internals

    private static byte[] aesKey(String ownPrivateKey, String peerPublicKey, String senderNonce, String requesterNonce) {
        byte[] xor = xorNonces(senderNonce, requesterNonce);
        byte[] salt = Arrays.copyOfRange(xor, 0, SALT_BYTES);
        byte[] secret = sharedSecret(ownPrivateKey, peerPublicKey);
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(secret, salt, null));
        byte[] key = new byte[AES_KEY_BYTES];
        hkdf.generateBytes(key, 0, AES_KEY_BYTES);
        return key;
    }

    private static byte[] iv(String senderNonce, String requesterNonce) {
        byte[] xor = xorNonces(senderNonce, requesterNonce);
        return Arrays.copyOfRange(xor, xor.length - IV_BYTES, xor.length);
    }

    private static byte[] xorNonces(String senderNonce, String requesterNonce) {
        byte[] a = Base64.getDecoder().decode(senderNonce);
        byte[] b = Base64.getDecoder().decode(requesterNonce);
        if (a.length != NONCE_BYTES || b.length != NONCE_BYTES) {
            throw new IllegalArgumentException("Fidelius nonces must be 32 bytes");
        }
        byte[] xor = new byte[NONCE_BYTES];
        for (int i = 0; i < NONCE_BYTES; i++) {
            xor[i] = (byte) (a[i] ^ b[i]);
        }
        return xor;
    }

    private static byte[] sharedSecret(String privateKey, String publicKey) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM, PROVIDER);
            agreement.init(privateKey(privateKey));
            agreement.doPhase(publicKey(publicKey), true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Fidelius key agreement failed", e);
        }
    }

    private static PrivateKey privateKey(String base64) {
        try {
            BigInteger d = new BigInteger(Base64.getDecoder().decode(base64));
            return KeyFactory.getInstance(ALGORITHM, PROVIDER).generatePrivate(new ECPrivateKeySpec(d, CURVE_SPEC));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Fidelius private key", e);
        }
    }

    private static PublicKey publicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("Missing public key");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM, PROVIDER);
            if (base64.trim().length() == RAW_POINT_BASE64_LENGTH) {
                return factory.generatePublic(new ECPublicKeySpec(CURVE_SPEC.getCurve().decodePoint(bytes), CURVE_SPEC));
            }
            PublicKey key = factory.generatePublic(new X509EncodedKeySpec(bytes));
            if (!(key instanceof ECPublicKey ec) || !ec.getParameters().getCurve().equals(CURVE_SPEC.getCurve())) {
                throw new IllegalArgumentException("Public key is not on Curve25519");
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Fidelius public key", e);
        }
    }

    private static byte[] gcm(boolean encrypt, byte[] key, byte[] iv, byte[] input) {
        GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), GCM_TAG_BITS, iv, null));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int length = cipher.processBytes(input, 0, input.length, output, 0);
        try {
            length += cipher.doFinal(output, length);
        } catch (InvalidCipherTextException e) {
            throw new IllegalArgumentException("Fidelius payload failed authentication", e);
        }
        return length == output.length ? output : Arrays.copyOf(output, length);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
