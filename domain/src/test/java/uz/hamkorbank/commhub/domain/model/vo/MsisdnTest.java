package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/** MSISDN is accepted strictly as {@code 9989xxxxxxxx} (FR-1.4, §9.1). */
class MsisdnTest {

    @Test
    @DisplayName("a well-formed number is accepted")
    void acceptsCanonicalFormat() {
        // Act
        Msisdn msisdn = Msisdn.of("998901234567");

        // Assert
        assertThat(msisdn.value()).isEqualTo("998901234567");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+998901234567",
                "998 90 123 45 67",
                "99890123456",
                "9989012345678",
                "998801234567",
                "abc901234567",
                "0998901234567"
            })
    @DisplayName("everything outside 9989xxxxxxxx is rejected")
    void rejectsOtherFormats(String candidate) {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Msisdn.of(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+998901234567", "998 90 123-45-67", "(998)901234567"})
    @DisplayName("normalize strips separators before validating")
    void normalizeAcceptsCommonInputVariants(String candidate) {
        // Act
        Msisdn msisdn = Msisdn.normalize(candidate);

        // Assert
        assertThat(msisdn.value()).isEqualTo("998901234567");
    }

    @Test
    @DisplayName("DB-04: the masked form hides the subscriber part")
    void masksTheSubscriberPart() {
        // Arrange
        Msisdn msisdn = Msisdn.of("998901234567");

        // Act + Assert
        assertThat(msisdn.masked()).isEqualTo("99890***4567");
        assertThat(msisdn).hasToString("99890***4567");
    }

    @Test
    @DisplayName("normalize rejects a blank input")
    void normalizeRejectsBlankInput() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Msisdn.normalize(" "));
    }
}
