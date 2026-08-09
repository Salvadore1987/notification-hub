package uz.hamkorbank.commhub.adapter.out.persistence.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.persistence.json.MessageContentsJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/** The encrypting codec must be interchangeable with the plain one from the caller's point of view. */
class ContentCodecTest {

    private static final String KEY = "dGVzdC1jb250ZW50LWtleS0zMi1ieXRlcy1sb25nISE=";

    private final JsonCodec jsonCodec = new JsonCodec();

    @Test
    @DisplayName("message contents survive the trip through the cipher")
    void contentsRoundTrip() {
        // Arrange
        ContentCodec codec = codec(true);
        MessageContents contents = MessageContents.of(SmsContent.of("Код 1234", "HAMKORBANK"));

        // Act
        String stored = codec.write(MessageContentsJson.of(contents));
        MessageContents restored = codec.read(stored, MessageContentsJson.class).toDomain();

        // Assert
        assertThat(stored).doesNotContain("1234", "HAMKORBANK");
        assertThat(restored.requireForChannel(Channel.SMS)).isEqualTo(contents.requireForChannel(Channel.SMS));
    }

    @Test
    @DisplayName("merge variables survive it too — they hold the same secret one step earlier (FR-4.3)")
    void variablesRoundTrip() {
        // Arrange
        ContentCodec codec = codec(true);
        Map<String, String> variables = Map.of("CODE", "1234", "AMOUNT", "1 500 000");

        // Act
        String stored = codec.write(variables);

        // Assert
        assertThat(stored).doesNotContain("1500000", "1234");
        assertThat(codec.readStringMap(stored)).isEqualTo(variables);
    }

    @Test
    @DisplayName("a row written before encryption was switched on still reads")
    void readsCleartextRows() {
        // Arrange
        ContentCodec codec = codec(true);
        String cleartext = jsonCodec.write(Map.of("CODE", "1234"));

        // Act
        Map<String, String> read = codec.readStringMap(cleartext);

        // Assert
        assertThat(read).containsEntry("CODE", "1234");
    }

    @Test
    @DisplayName("with encryption off the column holds the same JSON the plain codec would write")
    void matchesThePlainCodecWhenDisabled() {
        // Arrange
        ContentCodec codec = codec(false);
        Map<String, String> variables = Map.of("CODE", "1234");

        // Act
        String stored = codec.write(variables);

        // Assert
        assertThat(stored).isEqualTo(jsonCodec.write(variables));
    }

    @Test
    @DisplayName("a null payload stays a null column")
    void keepsNullAsNull() {
        // Arrange
        ContentCodec codec = codec(true);

        // Act & Assert
        assertThat(codec.write(null)).isNull();
        assertThat(codec.read(null, MessageContentsJson.class)).isNull();
        assertThat(codec.readStringMap(null)).isEmpty();
    }

    private ContentCodec codec(boolean enabled) {
        ContentEncryptionProperties properties =
                new ContentEncryptionProperties(enabled, "k1", enabled ? Map.of("k1", KEY) : Map.of());
        return new ContentCodec(jsonCodec, new ContentCipher(properties));
    }
}
