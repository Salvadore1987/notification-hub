package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

/** What {@link ContentCipher} puts in a column, and what it accepts back out of one (DB-04). */
class ContentCipherTest {

    private static final String KEY_A = "dGVzdC1jb250ZW50LWtleS0zMi1ieXRlcy1sb25nISE=";
    private static final String KEY_B = "b3RoZXItY29udGVudC1rZXktMzItYnl0ZXMtbG5nISE=";
    private static final String CONTENT = "{\"sms\":{\"text\":\"Код 1234\",\"sender\":\"HAMKORBANK\"}}";

    @Test
    @DisplayName("an encrypted value round-trips and is stored as a versioned JSON scalar")
    void roundTripsThroughAScalarEnvelope() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));

        // Act
        String stored = cipher.encrypt(CONTENT);

        // Assert
        assertThat(stored).startsWith("\"CH1.k1.").endsWith("\"").doesNotContain("HAMKORBANK");
        assertThat(ContentCipher.isEncrypted(stored)).isTrue();
        assertThat(cipher.decrypt(stored)).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("the same content encrypts to a different value every time (fresh nonce)")
    void usesAFreshNoncePerValue() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));

        // Act
        String first = cipher.encrypt(CONTENT);
        String second = cipher.encrypt(CONTENT);

        // Assert
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("cleartext written before DB-04 was switched on is read back unchanged")
    void readsCleartextRowsBack() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));

        // Act
        String read = cipher.decrypt(CONTENT);

        // Assert
        assertThat(ContentCipher.isEncrypted(CONTENT)).isFalse();
        assertThat(read).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("with encryption off the value passes through in clear")
    void passesThroughWhenDisabled() {
        // Arrange
        ContentCipher cipher = cipher(false, "k1", Map.of());

        // Act
        String stored = cipher.encrypt(CONTENT);

        // Assert
        assertThat(cipher.isEnabled()).isFalse();
        assertThat(stored).isEqualTo(CONTENT);
        assertThat(cipher.decrypt(stored)).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("a value stays readable after the active key is rotated, while the old key is kept")
    void readsRowsWrittenWithARetiredKey() {
        // Arrange
        String beforeRotation = cipher(true, "k1", Map.of("k1", KEY_A)).encrypt(CONTENT);
        ContentCipher rotated = cipher(true, "k2", Map.of("k1", KEY_A, "k2", KEY_B));

        // Act
        String read = rotated.decrypt(beforeRotation);

        // Assert
        assertThat(read).isEqualTo(CONTENT);
        assertThat(rotated.encrypt(CONTENT)).startsWith("\"CH1.k2.");
    }

    @Test
    @DisplayName("a row whose key is gone fails loudly instead of yielding garbage")
    void rejectsAnUnknownKeyId() {
        // Arrange
        String written = cipher(true, "k1", Map.of("k1", KEY_A)).encrypt(CONTENT);
        ContentCipher withoutTheKey = cipher(true, "k2", Map.of("k2", KEY_B));

        // Act & Assert
        assertThatThrownBy(() -> withoutTheKey.decrypt(written))
                .isInstanceOf(DataRetrievalFailureException.class)
                .hasMessageContaining("k1");
    }

    @Test
    @DisplayName("content edited in place in the database does not decrypt (GCM authentication)")
    void rejectsATamperedValue() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));
        String stored = cipher.encrypt(CONTENT);
        int edited = stored.lastIndexOf('.') + 10;
        String tampered = stored.substring(0, edited) + flip(stored.charAt(edited)) + stored.substring(edited + 1);

        // Act & Assert
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(DataRetrievalFailureException.class);
    }

    @Test
    @DisplayName("a truncated envelope is reported, not silently accepted")
    void rejectsATruncatedEnvelope() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));

        // Act & Assert
        assertThatThrownBy(() -> cipher.decrypt("\"CH1.k1.AAAA\""))
                .isInstanceOf(DataRetrievalFailureException.class)
                .hasMessageContaining("CH1");
    }

    @Test
    @DisplayName("a null column stays null on both ways")
    void keepsNullAsNull() {
        // Arrange
        ContentCipher cipher = cipher(true, "k1", Map.of("k1", KEY_A));

        // Act & Assert
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    private static ContentCipher cipher(boolean enabled, String activeKeyId, Map<String, String> keys) {
        return new ContentCipher(new ContentEncryptionProperties(enabled, activeKeyId, keys));
    }

    /** Flips one base64 character of the payload, the way a rogue UPDATE would. */
    private static char flip(char original) {
        return original == 'A' ? 'B' : 'A';
    }
}
