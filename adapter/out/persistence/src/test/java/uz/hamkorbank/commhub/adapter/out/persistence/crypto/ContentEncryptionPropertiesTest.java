package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Encryption is the default, and a context that cannot honour it must not start (DB-04). */
class ContentEncryptionPropertiesTest {

    private static final String KEY = "dGVzdC1jb250ZW50LWtleS0zMi1ieXRlcy1sb25nISE=";

    @Test
    @DisplayName("unset properties mean encryption is on, which needs a key")
    void requiresAKeyByDefault() {
        // Arrange
        Map<String, String> noKeys = Map.of();

        // Act & Assert
        assertThatThrownBy(() -> new ContentEncryptionProperties(null, null, noKeys))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commhub.persistence.encryption.keys.k1");
    }

    @Test
    @DisplayName("an unset environment variable leaves the key blank, which is not a key")
    void rejectsABlankKey() {
        // Arrange
        Map<String, String> blank = new HashMap<>();
        blank.put("k1", "");

        // Act & Assert
        assertThatThrownBy(() -> new ContentEncryptionProperties(true, "k1", blank))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key that is not 32 bytes is rejected — AES-256 only")
    void rejectsAKeyOfTheWrongSize() {
        // Arrange
        Map<String, String> shortKey = Map.of("k1", "c2hvcnQta2V5");

        // Act & Assert
        assertThatThrownBy(() -> new ContentEncryptionProperties(true, "k1", shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AES-256");
    }

    @Test
    @DisplayName("a key id that would break the stored envelope is rejected")
    void rejectsAKeyIdWithASeparator() {
        // Arrange
        Map<String, String> dotted = Map.of("k.1", KEY);

        // Act & Assert
        assertThatThrownBy(() -> new ContentEncryptionProperties(true, "k.1", dotted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key id");
    }

    @Test
    @DisplayName("switching encryption off deliberately needs no key")
    void allowsNoKeysWhenDisabled() {
        // Arrange & Act
        ContentEncryptionProperties properties = new ContentEncryptionProperties(false, null, null);

        // Assert
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.activeKeyId()).isEqualTo(ContentEncryptionProperties.DEFAULT_ACTIVE_KEY_ID);
        assertThat(properties.decodedKeys()).isEmpty();
    }

    @Test
    @DisplayName("configured keys are decoded to raw AES material")
    void decodesConfiguredKeys() {
        // Arrange
        ContentEncryptionProperties properties = new ContentEncryptionProperties(true, "k1", Map.of("k1", KEY));

        // Act
        Map<String, byte[]> decoded = properties.decodedKeys();

        // Assert
        assertThat(decoded).containsOnlyKeys("k1");
        assertThat(decoded.get("k1")).hasSize(ContentEncryptionProperties.KEY_LENGTH_BYTES);
    }
}
