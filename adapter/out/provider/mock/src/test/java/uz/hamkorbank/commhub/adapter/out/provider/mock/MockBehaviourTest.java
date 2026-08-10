package uz.hamkorbank.commhub.adapter.out.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;

/** The rules of the local stand, one test per suffix (ADR-0041). */
class MockBehaviourTest {

    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");
    private static final ProviderMessageId PM_ID = ProviderMessageId.of("MOCK0000000000001");

    @Test
    @DisplayName("a number ending in 00 is accepted and reported delivered")
    void deliversByDefaultRule() {
        // Arrange + Act
        MockBehaviour behaviour = MockBehaviour.of("998901234500");

        // Assert
        assertThat(behaviour).isEqualTo(MockBehaviour.DELIVERED);
        assertThat(behaviour.ackFor(PM_ID, NOW).isAccepted()).isTrue();
        assertThat(behaviour.reportedStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    @DisplayName("a number ending in 01 is accepted and reported undelivered")
    void reportsUndelivered() {
        // Arrange + Act
        MockBehaviour behaviour = MockBehaviour.of("998901234501");

        // Assert
        assertThat(behaviour.ackFor(PM_ID, NOW).isAccepted()).isTrue();
        assertThat(behaviour.reportedStatus()).isEqualTo(MessageStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("a number ending in 02 gives no answer at all — it throws, so retry and failover happen")
    void throwsWhenThereIsNoAnswer() {
        // Arrange
        MockBehaviour behaviour = MockBehaviour.of("998901234502");

        // Act + Assert — вернуть ack здесь означало бы, что мок не воспроизводит ни ретрай, ни breaker
        assertThat(behaviour).isEqualTo(MockBehaviour.NO_ANSWER);
        assertThatExceptionOfType(MockProviderUnavailableException.class)
                .isThrownBy(() -> behaviour.ackFor(PM_ID, NOW));
    }

    @Test
    @DisplayName("a number ending in 03 is a blocking refusal: the breaker opens at once (§18.1 code 102)")
    void blocksTheProvider() {
        // Arrange + Act
        ProviderAck ack = MockBehaviour.of("998901234503").ackFor(PM_ID, NOW);

        // Assert
        assertThat(ack.isBlocking()).isTrue();
        assertThat(ack.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("a number ending in 04 is refused as an unusable address, which suppresses it (FR-5.1)")
    void refusesTheAddress() {
        // Arrange + Act
        ProviderAck ack = MockBehaviour.of("998901234504").ackFor(PM_ID, NOW);

        // Assert
        assertThat(ack.invalidRecipient()).isTrue();
        assertThat(ack.isRetryable()).isFalse();
        assertThat(ack.isBlocking()).isFalse();
    }

    @Test
    @DisplayName("an address too short to carry a rule is an ordinary, deliverable one")
    void toleratesShortAddresses() {
        // Arrange + Act + Assert
        assertThat(MockBehaviour.of("7")).isEqualTo(MockBehaviour.DELIVERED);
        assertThat(MockBehaviour.of(null)).isEqualTo(MockBehaviour.DELIVERED);
    }
}
