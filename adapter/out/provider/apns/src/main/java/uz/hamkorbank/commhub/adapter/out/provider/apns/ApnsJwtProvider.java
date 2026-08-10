package uz.hamkorbank.commhub.adapter.out.provider.apns;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The APNs provider authentication token, signed and rotated (PU-06).
 *
 * <p>Apple's token-based authentication is a JWT signed with an elliptic-curve {@code .p8} key. Its
 * rules are unusual enough to be worth restating, because they are what this class exists for: a token
 * must not be re-signed more often than every 20 minutes — Apple refuses a connection that keeps
 * presenting fresh ones — and must not be older than an hour. So it is neither "sign per request" nor
 * "sign once"; it is signed on a schedule inside that window (PU-06).
 *
 * <p>Written by hand rather than with a JWT library, for the same reason the FCM assertion is: this is
 * one header, three claims and one signature, and the alternative is a dependency whose only job here
 * is base64.
 *
 * <p>The one subtlety is the signature format. {@code SHA256withECDSA} in the JDK produces a DER
 * sequence of two integers; JWS requires the raw {@code R‖S} pair, fixed at 32 bytes each. Handing
 * Apple the DER form yields {@code 403 InvalidProviderToken} with no hint as to why, so
 * {@link #toJose(byte[])} does the conversion — the single piece of cryptography in this package that
 * cannot be read off the specification.
 */
public class ApnsJwtProvider {

    /** P-256 coordinate size; both halves of a JOSE ES256 signature are exactly this long. */
    private static final int COORDINATE_LENGTH = 32;

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

    private final ApnsProperties.Credentials credentials;
    private final AtomicReference<SignedToken> cached = new AtomicReference<>();

    public ApnsJwtProvider(ApnsProperties.Credentials credentials) {
        this.credentials = Guard.notNull(credentials, "credentials");
    }

    /**
     * A token valid at {@code now}, re-signing when the previous one has aged past the refresh window.
     *
     * @param privateKeyPem the {@code .p8} contents, resolved from the secret store per call (SEC-04);
     *     a rotated key produces a new token immediately, because the cached one no longer matches it
     */
    public String tokenAt(String privateKeyPem, Instant now) {
        Guard.notBlank(privateKeyPem, "privateKeyPem");
        Guard.notNull(now, "now");
        SignedToken token = cached.get();
        if (token != null && token.isUsable(privateKeyPem, now, credentials.refreshInterval())) {
            return token.value();
        }
        SignedToken fresh = sign(privateKeyPem, now);
        cached.set(fresh);
        return fresh.value();
    }

    /**
     * Drops the cached token (PU-08).
     *
     * <p>Called on {@code ExpiredProviderToken} and {@code InvalidProviderToken}: Apple has stated the
     * token is unusable, and waiting out the refresh interval would refuse every iOS notification in the
     * meantime.
     */
    public void invalidate() {
        cached.set(null);
    }

    private SignedToken sign(String privateKeyPem, Instant now) {
        String headerJson = "{\"alg\":\"ES256\",\"kid\":\"%s\"}".formatted(credentials.keyId());
        String claimsJson = "{\"iss\":\"%s\",\"iat\":%d}".formatted(credentials.teamId(), now.getEpochSecond());
        String signingInput = base64(headerJson.getBytes(StandardCharsets.UTF_8))
                + '.'
                + base64(claimsJson.getBytes(StandardCharsets.UTF_8));
        String token = signingInput + '.' + base64(toJose(signature(privateKeyPem, signingInput)));
        return new SignedToken(privateKeyPem.hashCode(), token, now);
    }

    private static byte[] signature(String privateKeyPem, String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(readP8(privateKeyPem));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw ProviderCallException.blocking(
                    "BAD_APNS_KEY", "the APNs provider token could not be signed: " + e.getMessage());
        }
    }

    /** The EC private key from the {@code .p8} file Apple hands out (PKCS#8, PEM). */
    private static PrivateKey readP8(String pem) {
        String base64 = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw ProviderCallException.blocking("BAD_APNS_KEY", "the APNs .p8 key cannot be read: " + e.getMessage());
        }
    }

    /**
     * DER {@code SEQUENCE(INTEGER r, INTEGER s)} → the fixed 64-byte {@code R‖S} of JWS (RFC 7515).
     *
     * <p>Both integers are DER-encoded, which means they are signed and variable-length: a leading zero
     * appears whenever the high bit is set, and leading zeros are dropped whenever it is not. Copying
     * them right-aligned into 32-byte halves is what removes both.
     */
    private static byte[] toJose(byte[] der) {
        try {
            int offset = 3;
            if (der[1] == (byte) 0x81) {
                offset = 4;
            }
            int rLength = der[offset] & 0xFF;
            int sOffset = offset + rLength + 2;
            int sLength = der[sOffset] & 0xFF;
            byte[] jose = new byte[2 * COORDINATE_LENGTH];
            copyRightAligned(der, offset + 1, rLength, jose, 0);
            copyRightAligned(der, sOffset + 1, sLength, jose, COORDINATE_LENGTH);
            return jose;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw ProviderCallException.blocking(
                    "BAD_APNS_KEY", "the ECDSA signature could not be converted to the JWS format");
        }
    }

    private static void copyRightAligned(byte[] source, int from, int length, byte[] target, int at) {
        BigInteger value = new BigInteger(1, source, from, length);
        byte[] bytes = value.toByteArray();
        int start = bytes.length > COORDINATE_LENGTH ? bytes.length - COORDINATE_LENGTH : 0;
        int size = Math.min(bytes.length, COORDINATE_LENGTH);
        System.arraycopy(bytes, start, target, at + COORDINATE_LENGTH - size, size);
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** A signed token, the key it was signed with, and the moment it was signed. */
    private record SignedToken(int keyFingerprint, String value, Instant signedAt) {

        private boolean isUsable(String privateKeyPem, Instant now, Duration refreshInterval) {
            return keyFingerprint == privateKeyPem.hashCode()
                    && now.isBefore(signedAt.plus(refreshInterval))
                    && !now.isBefore(signedAt);
        }
    }
}
