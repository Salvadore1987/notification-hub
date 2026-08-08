package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * One non-delivery report, read out of the bounce mailbox (EM-02, RFC 3464).
 *
 * <p>It is the email equivalent of a provider DLR, and it is read the same way: the adapter translates the
 * mail server's vocabulary and the use case applies the canonical status (AD-06). What makes it harder than
 * a webhook is attribution — a report is a new email, not a callback carrying our identifier — which is why
 * two identifiers travel here and why the Hub writes its own {@code Message-ID} in the first place.
 *
 * @param hubMessageId identifier from the returned {@code X-Comm-Message-Id} or from the {@code Message-ID}
 *     the Hub wrote; empty when the report returned neither and nothing can be attributed
 * @param originalMessageId the {@code Message-ID} as it appeared, kept for the audit trail and for the
 *     operator who has to find the report in the mailbox afterwards
 * @param recipient address the report is about, as the report spells it
 * @param action {@code failed}, {@code delayed} or {@code delivered} (RFC 3464 §2.3.3)
 * @param status enhanced status code of RFC 3463, e.g. {@code 5.1.1}; empty for a report that carried none
 * @param diagnosticCode what the receiving server actually said, for the audit trail
 */
public record BounceReport(
        MessageId hubMessageId,
        String originalMessageId,
        String recipient,
        String action,
        String status,
        String diagnosticCode) {

    public static final String ACTION_FAILED = "failed";
    public static final String ACTION_DELAYED = "delayed";
    public static final String ACTION_DELIVERED = "delivered";

    public Optional<MessageId> hubMessageIdOptional() {
        return Optional.ofNullable(hubMessageId);
    }

    public Optional<String> statusOptional() {
        return Optional.ofNullable(status);
    }

    public Optional<String> recipientOptional() {
        return Optional.ofNullable(recipient);
    }

    /** Whether the report names a message the Hub can find. */
    public boolean isAttributable() {
        return hubMessageId != null;
    }
}
