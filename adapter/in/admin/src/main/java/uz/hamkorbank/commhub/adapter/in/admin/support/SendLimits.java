package uz.hamkorbank.commhub.adapter.in.admin.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of a panel-initiated send (ADR-0038).
 *
 * @param maxRecipients rows one uploaded list may carry. A ceiling exists so that an operator who
 *     exports the wrong sheet is told so instead of finding out from the invoice; it is announced as a
 *     400 rather than silently truncating, because a truncated send looks like a complete one
 */
@ConfigurationProperties("commhub.admin.send")
public record SendLimits(Integer maxRecipients) {

    public static final int DEFAULT_MAX_RECIPIENTS = 50_000;

    public SendLimits {
        maxRecipients = maxRecipients == null ? DEFAULT_MAX_RECIPIENTS : maxRecipients;
        if (maxRecipients < 1) {
            throw new IllegalArgumentException("commhub.admin.send.max-recipients must be positive");
        }
    }

    public static SendLimits defaults() {
        return new SendLimits(null);
    }
}
