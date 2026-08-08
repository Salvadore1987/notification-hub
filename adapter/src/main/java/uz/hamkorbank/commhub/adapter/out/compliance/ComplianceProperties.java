package uz.hamkorbank.commhub.adapter.out.compliance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import uz.hamkorbank.commhub.application.policy.EmailPolicy;
import uz.hamkorbank.commhub.application.policy.PushPolicy;
import uz.hamkorbank.commhub.domain.model.content.PushContent;

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
 * @param email content limits of the email channel (EM-01); they sit here because the validator reads all of
 *     its ceilings from one place, and an attachment limit is a rule of the Hub about content rather than a
 *     property of whichever relay ends up carrying the message
 * @param push content and fan-out limits of the push channel (PU-09, PU-11), here for the same reason
 */
@ConfigurationProperties("commhub.compliance")
public record ComplianceProperties(
        FrequencyCap frequencyCap, Boolean panBlocking, Duration counterRetention, Email email, Push push) {

    public static final Duration DEFAULT_COUNTER_RETENTION = Duration.ofDays(7);

    public ComplianceProperties {
        frequencyCap = frequencyCap == null ? new FrequencyCap(null, null, null) : frequencyCap;
        panBlocking = panBlocking == null || panBlocking;
        counterRetention = counterRetention == null ? DEFAULT_COUNTER_RETENTION : counterRetention;
        email = email == null ? new Email(null, null, null) : email;
        push = push == null ? new Push(null, null) : push;
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

    /**
     * Attachment ceilings of the email channel (EM-01).
     *
     * <p>They are deliberately expressed in bytes of stored payload, which is what the source system knows
     * and what the Hub can check before anything is encoded. Base64 adds about a third on the wire, so the
     * defaults sit below what a corporate relay usually accepts rather than at it.
     */
    public record Email(Integer maxAttachments, DataSize maxAttachmentSize, DataSize maxTotalAttachmentSize) {

        public Email {
            maxAttachments =
                    maxAttachments == null || maxAttachments < 0 ? EmailPolicy.DEFAULT_MAX_ATTACHMENTS : maxAttachments;
            maxAttachmentSize = maxAttachmentSize == null
                    ? DataSize.ofBytes(EmailPolicy.DEFAULT_MAX_ATTACHMENT_BYTES)
                    : maxAttachmentSize;
            maxTotalAttachmentSize = maxTotalAttachmentSize == null
                    ? DataSize.ofBytes(EmailPolicy.DEFAULT_MAX_TOTAL_BYTES)
                    : maxTotalAttachmentSize;
            if (maxTotalAttachmentSize.toBytes() < maxAttachmentSize.toBytes()) {
                throw new IllegalArgumentException(
                        "commhub.compliance.email.max-total-attachment-size (%s) is below max-attachment-size (%s): "
                                        .formatted(maxTotalAttachmentSize, maxAttachmentSize)
                                + "a single file within its own limit would always breach the total (EM-01)");
            }
        }

        public EmailPolicy toPolicy() {
            return new EmailPolicy(maxAttachments, maxAttachmentSize.toBytes(), maxTotalAttachmentSize.toBytes());
        }
    }

    /**
     * Ceilings of the push channel (PU-09, PU-11).
     *
     * <p>Configurable but not meant to be raised: {@code maxPayloadSize} is the platforms' own 4 KiB and
     * a larger value only moves the refusal from the Hub to APNs, one call per device later. The knob
     * exists because both platforms have historically had larger limits for some push types, and a
     * ceiling that cannot be corrected without a release is a ceiling that will be wrong.
     *
     * @param maxTokensPerMessage devices one submission may address; a fan-out bound, not a platform rule
     */
    public record Push(DataSize maxPayloadSize, Integer maxTokensPerMessage) {

        public Push {
            maxPayloadSize = maxPayloadSize == null ? DataSize.ofBytes(PushContent.MAX_PAYLOAD_BYTES) : maxPayloadSize;
            maxTokensPerMessage = maxTokensPerMessage == null || maxTokensPerMessage < 1
                    ? PushPolicy.DEFAULT_MAX_TOKENS_PER_MESSAGE
                    : maxTokensPerMessage;
            if (maxPayloadSize.toBytes() <= 0) {
                throw new IllegalArgumentException("commhub.compliance.push.max-payload-size must be positive");
            }
        }

        public PushPolicy toPolicy() {
            return new PushPolicy(Math.toIntExact(maxPayloadSize.toBytes()), maxTokensPerMessage);
        }
    }
}
