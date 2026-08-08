package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Component;

/**
 * Encrypts message content on its way into the database and decrypts it on the way back (DB-04).
 *
 * <p><strong>Application-level, not pgcrypto.</strong> {@code pgcrypto} would need the key inside every
 * statement, where it lands in {@code pg_stat_statements}, in the slow-query log and in a wire capture,
 * and it would have to be handed to the analytics replica as well (DB-06). Here the key never leaves the
 * application: the database — its backups, its WAL, its replica, its detached partitions — only ever
 * holds ciphertext.
 *
 * <p><strong>AES-256-GCM</strong> with a fresh 96-bit nonce per value and a 128-bit tag, so a stored
 * value is authenticated: a content blob edited in place, or copied from another row, fails to decrypt
 * instead of quietly changing what a client receives.
 *
 * <p>A value is stored as a JSON string scalar, {@code "CH1.<keyId>.<base64url(nonce‖ciphertext)>"}, and
 * that shape is what tells ciphertext from cleartext on read. A cleartext column of this schema always
 * holds a JSON object or array, PostgreSQL keeps a scalar a scalar, and base64url needs no escaping — so
 * the check is the first character, with no parse and no marker key that {@code jsonb} could reorder.
 * Reads accept both forms, which is what lets encryption be switched on for a database that already has
 * rows in it, and lets a rotated-out key be kept for as long as its rows live.
 */
@Component
public class ContentCipher {

    /** Version of the stored envelope; a future scheme gets its own prefix rather than a flag. */
    static final String VERSION = "CH1";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final char SEPARATOR = '.';

    private final boolean enabled;
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public ContentCipher(ContentEncryptionProperties properties) {
        this.enabled = properties.enabled();
        this.activeKeyId = properties.activeKeyId();
        Map<String, SecretKey> byId = new LinkedHashMap<>();
        properties.decodedKeys().forEach((keyId, material) -> byId.put(keyId, new SecretKeySpec(material, ALGORITHM)));
        this.keys = Map.copyOf(byId);
    }

    /** Whether new values are written encrypted; reads handle both forms regardless. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Wraps a JSON document into an encrypted scalar; returns it unchanged when encryption is off.
     *
     * @param json serialized column value, or {@code null} for a null column
     */
    public String encrypt(String json) {
        if (json == null || !enabled) {
            return json;
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        String header = VERSION + SEPARATOR + activeKeyId;
        byte[] ciphertext = run(Cipher.ENCRYPT_MODE, activeKeyId, nonce, header, json.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, envelope, 0, nonce.length);
        System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
        return '"'
                + header
                + SEPARATOR
                + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
                + '"';
    }

    /** Unwraps a stored value; a cleartext column — written before DB-04 was switched on — passes through. */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        String envelope = stored.trim();
        String body = envelope.substring(1, envelope.length() - 1);
        int keyIdStart = body.indexOf(SEPARATOR) + 1;
        int payloadStart = keyIdStart == 0 ? 0 : body.indexOf(SEPARATOR, keyIdStart) + 1;
        byte[] decoded = payloadStart == 0 ? new byte[0] : decodePayload(body.substring(payloadStart));
        if (decoded.length <= NONCE_LENGTH_BYTES) {
            throw new DataRetrievalFailureException(
                    "Stored content is not a readable %s envelope (DB-04)".formatted(VERSION));
        }
        String keyId = body.substring(keyIdStart, payloadStart - 1);
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        System.arraycopy(decoded, 0, nonce, 0, NONCE_LENGTH_BYTES);
        byte[] ciphertext = new byte[decoded.length - NONCE_LENGTH_BYTES];
        System.arraycopy(decoded, NONCE_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
        return new String(
                run(Cipher.DECRYPT_MODE, keyId, nonce, body.substring(0, payloadStart - 1), ciphertext),
                StandardCharsets.UTF_8);
    }

    /**
     * Whether a stored column holds an encrypted value.
     *
     * <p>Every cleartext column this cipher guards holds a JSON object or array, so a scalar — a value
     * starting with a quote — can only have come from {@link #encrypt}.
     */
    public static boolean isEncrypted(String stored) {
        if (stored == null) {
            return false;
        }
        String trimmed = stored.trim();
        return trimmed.length() > 2 && trimmed.charAt(0) == '"' && trimmed.startsWith(VERSION, 1);
    }

    private static byte[] decodePayload(String base64) {
        try {
            return Base64.getUrlDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new DataRetrievalFailureException(
                    "Stored content is not a readable %s envelope (DB-04)".formatted(VERSION), e);
        }
    }

    private byte[] run(int mode, String keyId, byte[] nonce, String aad, byte[] input) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new DataRetrievalFailureException(
                    ("Content key '%s' is not configured; keep every key a stored row was written with in "
                                    + "commhub.persistence.encryption.keys until its partition is gone (DB-03, DB-04)")
                            .formatted(keyId));
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new DataRetrievalFailureException(
                    "Failed to %s message content with key '%s' (DB-04)"
                            .formatted(mode == Cipher.ENCRYPT_MODE ? "encrypt" : "decrypt", keyId),
                    e);
        }
    }
}
