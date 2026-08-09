package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties.Sending;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/** The error and priority tables of §18.1 and PM-03 — the whole of what makes Playmobile Playmobile. */
class PlaymobileErrorCatalogTest {

    @Test
    @DisplayName("§18.1: 100 is transient on the provider's side and is retried")
    void internalServerErrorIsRetryable() {
        // Act + Assert
        assertThat(PlaymobileErrorCatalog.classify("100")).isEqualTo(ErrorClass.RETRYABLE);
    }

    @Test
    @DisplayName("§18.1: 102 Account lock is blocking — the breaker opens and the channel fails over")
    void accountLockIsBlocking() {
        // Act + Assert
        assertThat(PlaymobileErrorCatalog.classify("102")).isEqualTo(ErrorClass.BLOCKING);
        assertThat(PlaymobileErrorCatalog.describe("102", null)).isEqualTo("Account lock");
    }

    @ParameterizedTest
    @ValueSource(strings = {"101", "103", "104", "105", "202", "206", "301", "306", "401", "404", "406", "411"})
    @DisplayName("§18.1: content and parameter errors are permanent for the message")
    void contentAndParameterErrorsAreNonRetryable(String code) {
        // Act + Assert
        assertThat(PlaymobileErrorCatalog.classify(code)).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(PlaymobileErrorCatalog.isKnown(code)).isTrue();
    }

    @Test
    @DisplayName("§18.1: an undocumented code is refused rather than retried, and is recognisable as unknown")
    void undocumentedCodeIsNonRetryable() {
        // Act + Assert
        assertThat(PlaymobileErrorCatalog.classify("999")).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(PlaymobileErrorCatalog.isKnown("999")).isFalse();
        assertThat(PlaymobileErrorCatalog.describe("999", null)).contains("undocumented");
    }

    @Test
    @DisplayName("§18.1: the provider's own description wins over the table")
    void providerDescriptionWins() {
        // Act + Assert
        assertThat(PlaymobileErrorCatalog.describe("102", "Account is locked until 18:00"))
                .isEqualTo("Account is locked until 18:00");
    }

    @Test
    @DisplayName("PM-03: the traffic class sets the priority — OTP realtime, transactional normal, bulk low")
    void mapsTrafficClassOntoPriority() {
        // Arrange
        Sending sending = Sending.defaults();

        // Act + Assert
        assertThat(sending.priorityOf(TrafficClass.CRITICAL_OTP, Priority.NORMAL))
                .isEqualTo("realtime");
        assertThat(sending.priorityOf(TrafficClass.TRANSACTIONAL, Priority.NORMAL))
                .isEqualTo("normal");
        assertThat(sending.priorityOf(TrafficClass.NOTIFICATION, Priority.NORMAL))
                .isEqualTo("low");
    }

    @Test
    @DisplayName("TC-01: a bulk message cannot buy the OTP lane by claiming a high priority")
    void bulkMessageCannotRaiseItselfIntoTheOtpLane() {
        // Arrange
        Sending sending = Sending.defaults();

        // Act + Assert
        assertThat(sending.priorityOf(TrafficClass.NOTIFICATION, Priority.REALTIME))
                .isEqualTo("low");
        // Inside a class that is not bulk, an urgent message may still be raised.
        assertThat(sending.priorityOf(TrafficClass.TRANSACTIONAL, Priority.HIGH))
                .isEqualTo("high");
    }
}
