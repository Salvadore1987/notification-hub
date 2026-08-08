package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Quiet-hours window configured on a channel and/or a stream (FR-5.3).
 *
 * <p>Applies to bulk traffic only: OTP and transactional messages are never held back
 * ({@link TrafficClass#respectsQuietHours()}). The window may wrap over midnight, e.g. 22:00–08:00.
 * The default zone is {@code Asia/Tashkent}; a window is evaluated in the recipient's local time when
 * the message carries the {@code localTime} flag (Playmobile {@code timing.localtime}).
 *
 * @param behavior defer the message until the window closes, or reject it
 */
public record QuietHours(LocalTime start, LocalTime end, ZoneId zone, QuietHoursBehavior behavior) {

    /** Default business time zone of the Bank (FR-5.3, UI-04). */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tashkent");

    public QuietHours {
        Guard.notNull(start, "QuietHours.start");
        Guard.notNull(end, "QuietHours.end");
        Guard.notNull(behavior, "QuietHours.behavior");
        Guard.isTrue(!start.equals(end), "QuietHours.start and QuietHours.end must differ");
        zone = zone == null ? DEFAULT_ZONE : zone;
    }

    /** Window in {@code Asia/Tashkent} that defers messages until it closes. */
    public static QuietHours deferring(LocalTime start, LocalTime end) {
        return new QuietHours(start, end, DEFAULT_ZONE, QuietHoursBehavior.DEFER);
    }

    /** Window in {@code Asia/Tashkent} that rejects messages arriving inside it. */
    public static QuietHours rejecting(LocalTime start, LocalTime end) {
        return new QuietHours(start, end, DEFAULT_ZONE, QuietHoursBehavior.REJECT);
    }

    /** Whether the window is open (i.e. sending is forbidden) at the given instant. */
    public boolean isQuietAt(Instant now) {
        Guard.notNull(now, "now");
        LocalTime localTime = now.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !localTime.isBefore(start) && localTime.isBefore(end);
        }
        // Window wraps over midnight, e.g. 22:00-08:00.
        return !localTime.isBefore(start) || localTime.isBefore(end);
    }

    /** Whether the traffic class is subject to quiet hours at all (FR-5.3). */
    public boolean appliesTo(TrafficClass trafficClass) {
        Guard.notNull(trafficClass, "trafficClass");
        return trafficClass.respectsQuietHours();
    }

    /**
     * Instant at which sending may resume: {@code now} outside the window, otherwise the next
     * occurrence of {@link #end()} — used by the {@link QuietHoursBehavior#DEFER} behaviour (FR-5.3).
     */
    public Instant nextOpeningAt(Instant now) {
        if (!isQuietAt(now)) {
            return now;
        }
        ZonedDateTime zonedNow = now.atZone(zone);
        ZonedDateTime opening = zonedNow.with(end);
        if (!opening.isAfter(zonedNow)) {
            opening = opening.plusDays(1);
        }
        return opening.toInstant();
    }

    public boolean defersDelivery() {
        return behavior == QuietHoursBehavior.DEFER;
    }
}
