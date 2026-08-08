package uz.hamkorbank.commhub.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Scheduling and lifetime of a message (SRS §5.2, FR-3.4, FR-8.5).
 *
 * <p>Mirrors the Playmobile {@code timing} block so that deferred sends can be delegated to the
 * provider where supported (§9.1); providers without such support (SMS Gate) are served by the Hub
 * scheduler (SG-01).
 *
 * @param sendAfter earliest send instant; {@code null} means "immediately"
 * @param sendBefore latest send instant; {@code null} means "no window end"
 * @param ttl time to live counted from acceptance; {@code null} means "no TTL"
 * @param localTime interpret allowed hours in the recipient's local time (FR-5.3, Playmobile {@code localtime})
 * @param sendEvenly spread a bulk send evenly over the window (Playmobile {@code send-evenly})
 * @param allowedStartTime start of the allowed time-of-day window
 * @param allowedEndTime end of the allowed time-of-day window
 */
public record Timing(
        Instant sendAfter,
        Instant sendBefore,
        Duration ttl,
        boolean localTime,
        boolean sendEvenly,
        LocalTime allowedStartTime,
        LocalTime allowedEndTime) {

    public Timing {
        if (ttl != null) {
            Guard.isTrue(!ttl.isNegative() && !ttl.isZero(), "Timing.ttl must be positive");
        }
        if (sendAfter != null && sendBefore != null) {
            Guard.isTrue(sendBefore.isAfter(sendAfter), "Timing.sendBefore must be after Timing.sendAfter");
        }
        Guard.isTrue(
                (allowedStartTime == null) == (allowedEndTime == null),
                "Timing.allowedStartTime and Timing.allowedEndTime must be set together");
    }

    /** Send now, no expiry. */
    public static Timing immediate() {
        return new Timing(null, null, null, false, false, null, null);
    }

    /** Send now, expire after {@code ttl} — the usual OTP setup (FR-3.4). */
    public static Timing withTtl(Duration ttl) {
        return new Timing(null, null, ttl, false, false, null, null);
    }

    /** Deferred send inside a window (FR-8.5). */
    public static Timing scheduled(Instant sendAfter, Instant sendBefore) {
        return new Timing(sendAfter, sendBefore, null, false, false, null, null);
    }

    /** Instant at which the message expires: the earliest of {@code acceptedAt + ttl} and {@code sendBefore}. */
    public Optional<Instant> expiresAt(Instant acceptedAt) {
        Guard.notNull(acceptedAt, "acceptedAt");
        Instant ttlDeadline = ttl == null ? null : acceptedAt.plus(ttl);
        if (ttlDeadline == null) {
            return Optional.ofNullable(sendBefore);
        }
        if (sendBefore == null) {
            return Optional.of(ttlDeadline);
        }
        return Optional.of(ttlDeadline.isBefore(sendBefore) ? ttlDeadline : sendBefore);
    }

    /** Whether the message must be moved to {@code EXPIRED} (FR-3.4). */
    public boolean isExpiredAt(Instant now, Instant acceptedAt) {
        Guard.notNull(now, "now");
        return expiresAt(acceptedAt).filter(deadline -> !now.isBefore(deadline)).isPresent();
    }

    /** Whether the send window is open at {@code now}. */
    public boolean isSendableAt(Instant now) {
        Guard.notNull(now, "now");
        if (sendAfter != null && now.isBefore(sendAfter)) {
            return false;
        }
        return sendBefore == null || now.isBefore(sendBefore);
    }

    /** Whether the message must wait for its send window to open (FR-8.5). */
    public boolean isDeferredAt(Instant now) {
        Guard.notNull(now, "now");
        return sendAfter != null && now.isBefore(sendAfter);
    }

    public Optional<Duration> ttlOptional() {
        return Optional.ofNullable(ttl);
    }
}
