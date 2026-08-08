package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/** Quiet hours in Asia/Tashkent, including windows that wrap over midnight (FR-5.3). */
class QuietHoursTest {

    /** 2026-08-08 20:00 UTC = 2026-08-09 01:00 in Asia/Tashkent (UTC+5). */
    private static final Instant NIGHT = Instant.parse("2026-08-08T20:00:00Z");

    /** 2026-08-08 09:00 UTC = 14:00 in Asia/Tashkent. */
    private static final Instant AFTERNOON = Instant.parse("2026-08-08T09:00:00Z");

    @Test
    @DisplayName("the default zone is Asia/Tashkent and a wrapping window covers the night")
    void wrappingWindowCoversTheNight() {
        // Arrange
        QuietHours quietHours = QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0));

        // Act + Assert
        assertThat(quietHours.zone()).isEqualTo(QuietHours.DEFAULT_ZONE);
        assertThat(quietHours.isQuietAt(NIGHT)).isTrue();
        assertThat(quietHours.isQuietAt(AFTERNOON)).isFalse();
        assertThat(quietHours.defersDelivery()).isTrue();
    }

    @Test
    @DisplayName("a window inside one day only covers that interval")
    void nonWrappingWindow() {
        // Arrange
        QuietHours quietHours =
                new QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0), ZoneOffset.UTC, QuietHoursBehavior.REJECT);

        // Act + Assert
        assertThat(quietHours.isQuietAt(Instant.parse("2026-08-08T13:00:00Z"))).isTrue();
        assertThat(quietHours.isQuietAt(Instant.parse("2026-08-08T14:59:59Z"))).isTrue();
        assertThat(quietHours.isQuietAt(Instant.parse("2026-08-08T15:00:00Z"))).isFalse();
        assertThat(quietHours.defersDelivery()).isFalse();
    }

    @Test
    @DisplayName("FR-5.3: deferring returns the next end of the window")
    void nextOpeningIsTheEndOfTheWindow() {
        // Arrange
        QuietHours quietHours = QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0));

        // Act
        Instant opening = quietHours.nextOpeningAt(NIGHT);

        // Assert — 08:00 Asia/Tashkent on the same local day = 03:00 UTC on 2026-08-09.
        assertThat(opening).isEqualTo(Instant.parse("2026-08-09T03:00:00Z"));
        assertThat(quietHours.nextOpeningAt(AFTERNOON)).isEqualTo(AFTERNOON);
    }

    @Test
    @DisplayName("just before the window closes the opening is still today")
    void nextOpeningLaterTheSameDay() {
        // Arrange
        QuietHours quietHours = QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(8, 0));
        Instant earlyMorning = Instant.parse("2026-08-09T01:30:00Z"); // 06:30 Asia/Tashkent

        // Act + Assert
        assertThat(quietHours.nextOpeningAt(earlyMorning)).isEqualTo(Instant.parse("2026-08-09T03:00:00Z"));
    }

    @Test
    @DisplayName("FR-5.3: quiet hours apply to bulk traffic only")
    void appliesToBulkTrafficOnly() {
        // Arrange
        QuietHours quietHours = QuietHours.rejecting(LocalTime.of(22, 0), LocalTime.of(8, 0));

        // Act + Assert
        assertThat(quietHours.appliesTo(TrafficClass.NOTIFICATION)).isTrue();
        assertThat(quietHours.appliesTo(TrafficClass.TRANSACTIONAL)).isFalse();
        assertThat(quietHours.appliesTo(TrafficClass.CRITICAL_OTP)).isFalse();
    }

    @Test
    @DisplayName("an empty window is rejected")
    void rejectsEmptyWindow() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> QuietHours.deferring(LocalTime.of(22, 0), LocalTime.of(22, 0)))
                .withMessageContaining("must differ");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> QuietHours.deferring(null, LocalTime.of(8, 0)));
    }
}
