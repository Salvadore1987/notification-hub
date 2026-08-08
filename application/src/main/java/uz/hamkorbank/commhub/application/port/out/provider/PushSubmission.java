package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One push handed to an FCM or APNs adapter, addressed to a single device token (PU-01, PU-06).
 *
 * <p>A recipient with several active devices produces one submission per token; the aggregated status
 * per recipient is computed by the sending saga (PU-09). {@link #timing()} supplies the TTL that
 * becomes {@code android.ttl} / {@code apns-expiration} (PU-03, PU-06).
 *
 * @param collapseKey groups notifications that supersede each other; {@code null} when not used
 */
public record PushSubmission(
        ProviderRef provider,
        MessageId messageId,
        PushToken token,
        PushContent content,
        Timing timing,
        String collapseKey,
        SubmissionContext context) {

    public PushSubmission {
        Guard.notNull(provider, "PushSubmission.provider");
        Guard.notNull(messageId, "PushSubmission.messageId");
        Guard.notNull(token, "PushSubmission.token");
        Guard.notNull(content, "PushSubmission.content");
        Guard.notNull(timing, "PushSubmission.timing");
        Guard.notNull(context, "PushSubmission.context");
    }
}
