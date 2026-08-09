package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import java.util.Map;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;

/**
 * {@link JsonCodec} for the columns that hold what was written to the client (DB-04).
 *
 * <p>Same contract as the plain codec — serialize on the way in, deserialize on the way out — with
 * {@link ContentCipher} in between, so an adapter picks a codec by what the column holds and nothing
 * else changes. Which columns those are is a deliberate list, not "everything with PII in it":
 *
 * <ul>
 *   <li>{@code message.contents} — the rendered body, the OTP inside it, the email and its attachments;
 *   <li>{@code message.template_variables} — the merge values the body was built from (amounts, names,
 *       card tails), which are the same secret one indirection earlier (FR-4.3).
 * </ul>
 *
 * <p>{@code message.recipient} stays in clear on purpose: it carries the mandatory GIN index and the
 * lookups of DB-05, which ciphertext would make impossible. Recipient PII is covered by its own controls
 * — SHA-256 in {@code suppression_list} and masking in logs and UI (DB-04, SEC-06).
 */
@Component
public class ContentCodec {

    private final JsonCodec jsonCodec;
    private final ContentCipher cipher;

    public ContentCodec(JsonCodec jsonCodec, ContentCipher cipher) {
        this.jsonCodec = jsonCodec;
        this.cipher = cipher;
    }

    /** Serializes and encrypts a payload for a {@code jsonb} column; {@code null} stays {@code null}. */
    public String write(Object payload) {
        return cipher.encrypt(jsonCodec.write(payload));
    }

    /** Decrypts and deserializes a column; a {@code null} or blank column yields {@code null}. */
    public <T> T read(String stored, Class<T> type) {
        return jsonCodec.read(cipher.decrypt(stored), type);
    }

    /** Decrypts and deserializes an object of string values, e.g. the merge variables (FR-4.3). */
    public Map<String, String> readStringMap(String stored) {
        return jsonCodec.readStringMap(cipher.decrypt(stored));
    }
}
