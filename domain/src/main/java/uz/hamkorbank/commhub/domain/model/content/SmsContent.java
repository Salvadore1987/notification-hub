package uz.hamkorbank.commhub.domain.model.content;

import java.nio.charset.StandardCharsets;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * SMS payload: message text plus the alpha-name it is sent from (SRS §5.2, §9.1).
 *
 * <p>The segment count and the resulting cost are derived from {@link #text()} by
 * {@code SegmentCalculator} (MP-06, §18.3), not stored here.
 *
 * @param text message text, already rendered from its template when one is used
 * @param originator alpha-name (sender id); {@code null} means "take the provider default"
 */
public record SmsContent(String text, String originator) implements MessageContent {

    /** Seven concatenated GSM-7 segments (§18.3) — the practical upper bound for one SMS. */
    public static final int MAX_TEXT_LENGTH = 1071;

    public static final int MAX_ORIGINATOR_LENGTH = 11;

    public SmsContent {
        Guard.notBlank(text, "SmsContent.text");
        Guard.maxLength(text, MAX_TEXT_LENGTH, "SmsContent.text");
        Guard.maxLength(originator, MAX_ORIGINATOR_LENGTH, "SmsContent.originator");
    }

    public static SmsContent of(String text) {
        return new SmsContent(text, null);
    }

    public static SmsContent of(String text, String originator) {
        return new SmsContent(text, originator);
    }

    /** Returns a copy with the template-rendered text (FR-4.3). */
    public SmsContent withText(String renderedText) {
        return new SmsContent(renderedText, originator);
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public int payloadSizeBytes() {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
