package uz.hamkorbank.commhub.adapter.out.compliance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment settings of the compliance filters (FR-5.4, SEC-05).
 *
 * <p>Quiet hours are not here: they are configured per stream and per channel in the database and applied
 * without a restart (FR-5.3, AD-07). What stays in the yaml are the two knobs that are the same for the whole
 * installation — the frequency cap of bulk traffic and what a card number in content does.
 *
 * @param frequencyCap per-recipient cap of bulk traffic; {@code blocking} off is the MVP mode, counters and
 *     alerts only (FR-5.4)
 * @param panBlocking whether content carrying a card number is rejected; an SMS is rejected regardless
 *     (SEC-05)
 * @param counterRetention how long the per-recipient counters are kept; must cover the cap window, and there
 *     is no reason to keep more (DB-03)
 */
@ConfigurationProperties("commhub.compliance")
public record ComplianceProperties(FrequencyCap frequencyCap, Boolean panBlocking, Duration counterRetention) {

    public static final Duration DEFAULT_COUNTER_RETENTION = Duration.ofDays(7);

    public ComplianceProperties {
        frequencyCap = frequencyCap == null ? new FrequencyCap(null, null, null) : frequencyCap;
        panBlocking = panBlocking == null || panBlocking;
        counterRetention = counterRetention == null ? DEFAULT_COUNTER_RETENTION : counterRetention;
        if (counterRetention.compareTo(frequencyCap.window()) < 0) {
            throw new IllegalArgumentException(
                    "commhub.compliance.counter-retention (%s) must cover the cap window (%s), otherwise the sweep "
                                    .formatted(counterRetention, frequencyCap.window())
                            + "would delete the counters the cap is about to read (FR-5.4)");
        }
    }

    /**
     * Per-recipient cap of the {@code NOTIFICATION} class (FR-5.4).
     *
     * @param maxMessages messages allowed per recipient and channel inside the window
     * @param window length of the rolling window
     * @param blocking whether an exceeded cap rejects the message; off in the MVP
     */
    public record FrequencyCap(Integer maxMessages, Duration window, Boolean blocking) {

        public static final int DEFAULT_MAX_MESSAGES = 10;

        public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

        public FrequencyCap {
            maxMessages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
            window = window == null ? DEFAULT_WINDOW : window;
            blocking = blocking != null && blocking;
            if (maxMessages <= 0) {
                throw new IllegalArgumentException("commhub.compliance.frequency-cap.max-messages must be positive");
            }
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("commhub.compliance.frequency-cap.window must be positive");
            }
        }
    }
}
