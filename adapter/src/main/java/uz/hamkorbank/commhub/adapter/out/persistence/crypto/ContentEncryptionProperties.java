package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys of the at-rest encryption of message content (DB-04).
 *
 * <p>Encryption is on unless it is switched off deliberately, and a context that starts with it on but
 * without a usable key fails here rather than at the first insert: a silent fallback to plaintext is
 * exactly the failure this requirement exists to prevent.
 *
 * <p>Several keys may be configured at once — {@code activeKeyId} is the one new rows are written with,
 * the rest stay so that rows written before a rotation can still be read. A retired key may be dropped
 * only after the last partition holding rows encrypted with it has been detached (DB-03).
 *
 * @param enabled whether content is encrypted before it is stored; {@code true} by default
 * @param activeKeyId id of the key new rows are written with; recorded in every stored value
 * @param keys base64 of 32 raw bytes (AES-256) by key id; in production supplied from the secret store
 *     of the platform, never from a file in the repository
 */
@ConfigurationProperties("commhub.persistence.encryption")
public record ContentEncryptionProperties(Boolean enabled, String activeKeyId, Map<String, String> keys) {

    public static final String DEFAULT_ACTIVE_KEY_ID = "k1";

    /** AES-256: the only key size this adapter accepts. */
    public static final int KEY_LENGTH_BYTES = 32;

    /** A key id travels inside the stored value between dots, so it may not contain one. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public ContentEncryptionProperties {
        enabled = enabled == null || enabled;
        activeKeyId = activeKeyId == null || activeKeyId.isBlank() ? DEFAULT_ACTIVE_KEY_ID : activeKeyId;
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        if (enabled) {
            requireUsableKeys(activeKeyId, keys);
        }
    }

    /** Decoded keys by id; empty when encryption is off. */
    public Map<String, byte[]> decodedKeys() {
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        keys.forEach((keyId, material) -> decoded.put(keyId, Base64.getDecoder().decode(material.trim())));
        return decoded;
    }

    private static void requireUsableKeys(String activeKeyId, Map<String, String> keys) {
        String active = keys.get(activeKeyId);
        if (active == null || active.isBlank()) {
            throw new IllegalArgumentException(
                    ("commhub.persistence.encryption.keys.%s must hold the active content key (DB-04); "
                                    + "generate one with `openssl rand -base64 32`, or set "
                                    + "commhub.persistence.encryption.enabled=false to store content in clear")
                            .formatted(activeKeyId));
        }
        keys.forEach(ContentEncryptionProperties::requireUsableKey);
    }

    private static void requireUsableKey(String keyId, String material) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException(
                    "commhub.persistence.encryption.keys.%s is not a valid key id: expected %s"
                            .formatted(keyId, KEY_ID.pattern()));
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(material.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "commhub.persistence.encryption.keys.%s is not valid base64".formatted(keyId), e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "commhub.persistence.encryption.keys.%s must decode to %d bytes (AES-256), got %d"
                            .formatted(keyId, KEY_LENGTH_BYTES, decoded.length));
        }
    }
}
