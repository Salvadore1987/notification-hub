package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;

/** TTL and send window of a message (FR-3.4, FR-8.5). */
class TimingTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-08T10:00:00Z");

    @Test
    @DisplayName("immediate timing never expires and is always sendable")
    void immediateTimingHasNoDeadlines() {
        // Act
        Timing timing = Timing.immediate();

        // Assert
        assertThat(timing.expiresAt(ACCEPTED_AT)).isEmpty();
        assertThat(timing.isExpiredAt(ACCEPTED_AT.plus(Duration.ofDays(1)), ACCEPTED_AT))
                .isFalse();
        assertThat(timing.isSendableAt(ACCEPTED_AT)).isTrue();
        assertThat(timing.isDeferredAt(ACCEPTED_AT)).isFalse();
        assertThat(timing.ttlOptional()).isEmpty();
    }

    @Test
    @DisplayName("FR-3.4: a TTL expires relative to the acceptance instant")
    void ttlExpiresRelativeToAcceptance() {
        // Arrange
        Timing timing = Timing.withTtl(Duration.ofMinutes(5));

        // Act + Assert
        assertThat(timing.expiresAt(ACCEPTED_AT)).contains(ACCEPTED_AT.plus(Duration.ofMinutes(5)));
        assertThat(timing.isExpiredAt(ACCEPTED_AT.plus(Duration.ofMinutes(4)), ACCEPTED_AT))
                .isFalse();
        assertThat(timing.isExpiredAt(ACCEPTED_AT.plus(Duration.ofMinutes(5)), ACCEPTED_AT))
                .isTrue();
    }

    @Test
    @DisplayName("the earliest of TTL and window end wins")
    void expiryTakesTheEarliestDeadline() {
        // Arrange
        Instant windowEnd = ACCEPTED_AT.plus(Duration.ofMinutes(2));
        Timing ttlFirst = new Timing(
                null, ACCEPTED_AT.plus(Duration.ofHours(1)), Duration.ofMinutes(2), false, false, null, null);
        Timing windowFirst = new Timing(null, windowEnd, Duration.ofHours(1), false, false, null, null);

        // Act + Assert
        assertThat(ttlFirst.expiresAt(ACCEPTED_AT)).contains(windowEnd);
        assertThat(windowFirst.expiresAt(ACCEPTED_AT)).contains(windowEnd);
    }

    @Test
    @DisplayName("FR-8.5: a scheduled message is deferred until its window opens")
    void scheduledMessagesAreDeferred() {
        // Arrange
        Instant sendAfter = ACCEPTED_AT.plus(Duration.ofHours(2));
        Timing timing = Timing.scheduled(sendAfter, sendAfter.plus(Duration.ofHours(1)));

        // Act + Assert
        assertThat(timing.isDeferredAt(ACCEPTED_AT)).isTrue();
        assertThat(timing.isSendableAt(ACCEPTED_AT)).isFalse();
        assertThat(timing.isSendableAt(sendAfter)).isTrue();
        assertThat(timing.isSendableAt(sendAfter.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    @DisplayName("inconsistent timing is rejected")
    void rejectsInconsistentTiming() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Timing.withTtl(Duration.ZERO))
                .withMessageContaining("must be positive");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Timing.scheduled(ACCEPTED_AT, ACCEPTED_AT.minusSeconds(1)))
                .withMessageContaining("must be after");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Timing(null, null, null, true, false, LocalTime.of(9, 0), null))
                .withMessageContaining("must be set together");
    }
}
