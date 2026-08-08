package uz.hamkorbank.commhub.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OBS-03/SEC-05: the safety net under "mask at the point of writing". */
class LogMaskingTest {

    @Test
    @DisplayName("DB-04: an MSISDN keeps its shape and loses its middle")
    void masksMsisdn() {
        assertThat(LogMasking.mask("sending to 998901234567 now")).isEqualTo("sending to 99890***4567 now");
    }

    @Test
    @DisplayName("an e-mail keeps its domain: 'everything to this domain bounces' is what a log is read for")
    void masksEmailKeepingTheDomain() {
        assertThat(LogMasking.mask("bounce from ivan.petrov@example.com")).isEqualTo("bounce from i***v@example.com");
    }

    @Test
    @DisplayName("SEC-05: a card number keeps only its last four")
    void masksCardNumbers() {
        // 4012888888881881 is a well-known Luhn-valid test PAN.
        assertThat(LogMasking.mask("card 4012888888881881 in body")).isEqualTo("card ***1881 in body");
    }

    @Test
    @DisplayName("a long number that is not a card is left alone — ids and amounts must stay readable")
    void leavesNonLuhnNumbersAlone() {
        assertThat(LogMasking.mask("attempt 1234567890123 failed")).isEqualTo("attempt 1234567890123 failed");
    }

    @Test
    @DisplayName("several findings in one line are all masked")
    void masksEveryFindingInTheLine() {
        // Arrange
        String line = "998901234567 and 998907654321 complained via ivan@bank.uz";

        // Act
        String masked = LogMasking.mask(line);

        // Assert
        assertThat(masked).isEqualTo("99890***4567 and 99890***4321 complained via i***n@bank.uz");
    }

    @Test
    @DisplayName("text without personal data is returned unchanged, and null stays null")
    void leavesOrdinaryTextUntouched() {
        assertThat(LogMasking.mask("Outbox relay published 12 events")).isEqualTo("Outbox relay published 12 events");
        assertThat(LogMasking.mask(null)).isNull();
        assertThat(LogMasking.mask("")).isEmpty();
    }
}
