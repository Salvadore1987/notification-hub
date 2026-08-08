package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/** Email validation and masking (FR-1.4, DB-04). */
class EmailAddressTest {

    @Test
    @DisplayName("the domain part is normalised to lower case, the local part is kept")
    void normalisesTheDomainPart() {
        // Act
        EmailAddress email = EmailAddress.of("Ivan.Petrov@Hamkorbank.UZ");

        // Assert
        assertThat(email.value()).isEqualTo("Ivan.Petrov@hamkorbank.uz");
        assertThat(email.localPart()).isEqualTo("Ivan.Petrov");
        assertThat(email.domain()).isEqualTo("hamkorbank.uz");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "two@@at.uz", "@hamkorbank.uz", "ivan@", "ivan@localhost", "iv an@bank.uz"})
    @DisplayName("malformed addresses are rejected")
    void rejectsMalformedAddresses(String candidate) {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> EmailAddress.of(candidate));
    }

    @Test
    @DisplayName("an address longer than 254 characters is rejected")
    void rejectsOversizedAddresses() {
        // Arrange
        String local = "a".repeat(EmailAddress.MAX_LENGTH);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> EmailAddress.of(local + "@hamkorbank.uz"));
    }

    @Test
    @DisplayName("DB-04: the masked form keeps only the first and the last character of the local part")
    void masksTheLocalPart() {
        // Act + Assert
        assertThat(EmailAddress.of("ivan@hamkorbank.uz").masked()).isEqualTo("i***n@hamkorbank.uz");
        assertThat(EmailAddress.of("iv@hamkorbank.uz").masked()).isEqualTo("i***@hamkorbank.uz");
        assertThat(EmailAddress.of("ivan@hamkorbank.uz")).hasToString("i***n@hamkorbank.uz");
    }

    @Test
    @DisplayName("a blank address is rejected")
    void rejectsBlankAddress() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> EmailAddress.of("  "));
    }
}
