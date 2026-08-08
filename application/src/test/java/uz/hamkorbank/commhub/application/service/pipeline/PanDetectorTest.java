package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PCI DSS card-number detection in message content (SEC-05). */
class PanDetectorTest {

    private final PanDetector detector = new PanDetector();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111111111111111",
                "4111 1111 1111 1111",
                "4111-1111-1111-1111",
                "Karta 5555555555554444 blokirovana",
                "378282246310005"
            })
    @DisplayName("SEC-05: a Luhn-valid card number is detected in any of its usual spellings")
    void detectsCardNumbers(String text) {
        // Arrange + Act + Assert
        assertThat(detector.containsPan(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Kod: 123456",
                "Summa 1 250 000 UZS spisana",
                "Schet 20208000900000000001 popolnen",
                "4111111111111112",
                "998901234567"
            })
    @DisplayName("SEC-05: ordinary digits — codes, amounts, phone numbers — are not card numbers")
    void ignoresOrdinaryDigits(String text) {
        // Arrange + Act + Assert
        assertThat(detector.containsPan(text)).isFalse();
    }

    @Test
    @DisplayName("SEC-05: empty content carries no card number")
    void handlesEmptyText() {
        // Arrange + Act + Assert
        assertThat(detector.containsPan(null)).isFalse();
        assertThat(detector.containsPan("  ")).isFalse();
    }
}
