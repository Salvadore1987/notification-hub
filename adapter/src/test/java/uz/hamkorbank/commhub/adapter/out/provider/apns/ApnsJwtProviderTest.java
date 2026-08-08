package uz.hamkorbank.commhub.adapter.out.provider.apns;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APNs provider token (PU-06).
 *
 * <p>The signature is verified the way Apple verifies it — parsed as JWS, converted back from the raw
 * {@code R‖S} pair to DER and checked against the public key — because the DER-to-JOSE conversion is the
 * one place here where a plausible-looking token is silently rejected by the platform.
 */
class ApnsJwtProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");

    private static KeyPair keyPair;
    private static String privateKeyPem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        keyPair = generator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    @DisplayName("PU-06: the token is an ES256 JWS with the team as iss and the key id as kid")
    void signsTheDocumentedClaims() {
        // Arrange
        ApnsJwtProvider provider = provider(Duration.ofMinutes(40));

        // Act
        String token = provider.tokenAt(privateKeyPem, NOW);

        // Assert
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        assertThat(header.get("alg").asString()).isEqualTo("ES256");
        assertThat(header.get("kid").asString()).isEqualTo("KEY123");
        assertThat(claims.get("iss").asString()).isEqualTo("TEAM123");
        assertThat(claims.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("PU-06: the signature verifies against the public key — the JOSE conversion is what Apple reads")
    void producesASignatureApnsCanVerify() throws Exception {
        // Arrange — a hundred tokens, because the DER encoding of r and s varies in length by chance
        ApnsJwtProvider provider = provider(Duration.ofMinutes(20));

        // Act & Assert
        IntStream.range(0, 100).forEach(index -> {
            ApnsJwtProvider fresh = provider(Duration.ofMinutes(20));
            String token = fresh.tokenAt(privateKeyPem, NOW.plusSeconds(index));
            assertThat(verifies(token)).as("token %d verifies", index).isTrue();
        });
        assertThat(verifies(provider.tokenAt(privateKeyPem, NOW))).isTrue();
    }

    @Test
    @DisplayName("PU-06: the same token is reused inside the refresh window — Apple refuses a stream of fresh ones")
    void reusesTheTokenInsideTheWindow() {
        // Arrange
        ApnsJwtProvider provider = provider(Duration.ofMinutes(40));

        // Act
        String first = provider.tokenAt(privateKeyPem, NOW);
        String reused = provider.tokenAt(privateKeyPem, NOW.plus(Duration.ofMinutes(39)));
        String renewed = provider.tokenAt(privateKeyPem, NOW.plus(Duration.ofMinutes(41)));

        // Assert
        assertThat(reused).isEqualTo(first);
        assertThat(renewed).isNotEqualTo(first);
    }

    @Test
    @DisplayName("PU-08: a refused token is dropped, so the next message carries a fresh signature")
    void reSignsAfterInvalidation() {
        // Arrange
        ApnsJwtProvider provider = provider(Duration.ofMinutes(40));
        String first = provider.tokenAt(privateKeyPem, NOW);

        // Act
        provider.invalidate();
        String second = provider.tokenAt(privateKeyPem, NOW.plusSeconds(1));

        // Assert
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("SEC-04: a rotated key is signed with immediately, not at the end of the refresh window")
    void appliesARotatedKeyAtOnce() throws Exception {
        // Arrange
        ApnsJwtProvider provider = provider(Duration.ofMinutes(40));
        String first = provider.tokenAt(privateKeyPem, NOW);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair rotated = generator.generateKeyPair();
        String rotatedPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(rotated.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        // Act
        String second = provider.tokenAt(rotatedPem, NOW.plusSeconds(1));

        // Assert
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("PU-06: an interval outside the 20–60 minute window is clamped, not refused at startup")
    void clampsTheRefreshInterval() {
        assertThat(credentials(Duration.ofMinutes(1)).refreshInterval()).isEqualTo(Duration.ofMinutes(20));
        assertThat(credentials(Duration.ofHours(3)).refreshInterval()).isEqualTo(Duration.ofMinutes(55));
        assertThat(credentials(null).refreshInterval()).isEqualTo(ApnsProperties.Credentials.DEFAULT_REFRESH_INTERVAL);
    }

    /** Verifies the JWS the way a receiving party does: raw R‖S back to DER, then ECDSA verify. */
    private static boolean verifies(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] jose = Base64.getUrlDecoder().decode(parts[2]);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(keyPair.getPublic());
            signature.update((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(toDer(jose));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The inverse of the conversion under test: {@code R‖S} → {@code SEQUENCE(INTEGER, INTEGER)}. */
    private static byte[] toDer(byte[] jose) {
        byte[] r = trim(java.util.Arrays.copyOfRange(jose, 0, 32));
        byte[] s = trim(java.util.Arrays.copyOfRange(jose, 32, 64));
        int length = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + length];
        der[0] = 0x30;
        der[1] = (byte) length;
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    /** Minimal signed big-endian form: drop leading zeros, prepend one when the high bit is set. */
    private static byte[] trim(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        byte[] trimmed = java.util.Arrays.copyOfRange(value, start, value.length);
        if ((trimmed[0] & 0x80) == 0) {
            return trimmed;
        }
        byte[] padded = new byte[trimmed.length + 1];
        System.arraycopy(trimmed, 0, padded, 1, trimmed.length);
        return padded;
    }

    private static JsonNode decode(String part) {
        return JsonMapper.builder()
                .build()
                .readTree(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
    }

    private static ApnsJwtProvider provider(Duration refreshInterval) {
        return new ApnsJwtProvider(credentials(refreshInterval));
    }

    private static ApnsProperties.Credentials credentials(Duration refreshInterval) {
        return new ApnsProperties.Credentials("TEAM123", "KEY123", "apns/key.p8", refreshInterval);
    }
}
