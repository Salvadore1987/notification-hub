package uz.hamkorbank.commhub.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

class UuidV7Test {

    private static final long EPOCH_MILLIS = 1_786_000_000_000L;

    @Test
    @DisplayName("generate stamps the requested millisecond and sets version 7 with variant 2")
    void generateProducesUuidV7() {
        // Act
        UUID uuid = UuidV7.generate(EPOCH_MILLIS);

        // Assert
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(UuidV7.timestampOf(uuid)).isEqualTo(Instant.ofEpochMilli(EPOCH_MILLIS));
        assertThat(UuidV7.isUuidV7(uuid)).isTrue();
    }

    @Test
    @DisplayName("identifiers of the same millisecond stay strictly increasing")
    void generateIsMonotonicWithinTheSameMillisecond() {
        // Arrange
        UuidV7.generate(EPOCH_MILLIS);

        // Act
        UUID first = UuidV7.generate(EPOCH_MILLIS);
        UUID second = UuidV7.generate(EPOCH_MILLIS);

        // Assert
        assertThat(first.getMostSignificantBits()).isLessThan(second.getMostSignificantBits());
    }

    @Test
    @DisplayName("generate produces unique identifiers")
    void generateProducesUniqueValues() {
        // Arrange
        Set<UUID> generated = new HashSet<>();

        // Act
        for (int index = 0; index < 1_000; index++) {
            generated.add(UuidV7.generate());
        }

        // Assert
        assertThat(generated).hasSize(1_000);
    }

    @Test
    @DisplayName("the current clock is used by default")
    void generateUsesTheCurrentClock() {
        // Act
        UUID uuid = UuidV7.generate();

        // Assert
        assertThat(UuidV7.timestampOf(uuid))
                .isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("a negative timestamp is rejected")
    void generateRejectsNegativeTimestamp() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> UuidV7.generate(-1L));
    }

    @Test
    @DisplayName("timestampOf rejects identifiers of another version")
    void timestampOfRejectsOtherVersions() {
        // Arrange
        UUID uuidV4 = UUID.randomUUID();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> UuidV7.timestampOf(uuidV4));
        assertThat(UuidV7.isUuidV7(uuidV4)).isFalse();
        assertThat(UuidV7.isUuidV7(null)).isFalse();
    }
}
