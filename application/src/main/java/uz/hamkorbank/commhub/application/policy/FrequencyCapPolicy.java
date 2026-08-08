package uz.hamkorbank.commhub.application.policy;

import java.time.Duration;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Frequency cap for bulk traffic (FR-5.4).
 *
 * <p>MVP behaviour is counters plus alerts: {@link #blocking()} is off, so an exceeded cap raises a
 * metric and an alert but still lets the message through. Switching it on turns the cap into a
 * rejection with {@code FREQUENCY_CAPPED} without any code change.
 *
 * @param maxMessages messages allowed per recipient and channel inside {@link #window()}
 */
public record FrequencyCapPolicy(int maxMessages, Duration window, boolean blocking) {

    public FrequencyCapPolicy {
        Guard.positive(maxMessages, "FrequencyCapPolicy.maxMessages");
        Guard.notNull(window, "FrequencyCapPolicy.window");
        Guard.isTrue(!window.isNegative() && !window.isZero(), "FrequencyCapPolicy.window must be positive");
    }

    /** Ten bulk messages per recipient per day, alerting only (FR-5.4, MVP). */
    public static FrequencyCapPolicy defaults() {
        return new FrequencyCapPolicy(10, Duration.ofHours(24), false);
    }

    public boolean isExceededBy(long observed) {
        return observed >= maxMessages;
    }
}
