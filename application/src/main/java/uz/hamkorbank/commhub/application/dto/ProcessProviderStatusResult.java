package uz.hamkorbank.commhub.application.dto;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * Outcome of processing a provider status report (AD-06, PM-02, SG-02).
 *
 * <p>Provider callbacks are retried by the providers themselves, so the use case is idempotent:
 * {@code applied == false} means the report changed nothing — unknown message, status already
 * recorded or the message already terminal — and the adapter still answers 200.
 *
 * @param messageId {@code null} when no message matched the provider identifiers
 */
public record ProcessProviderStatusResult(MessageId messageId, MessageStatus status, boolean applied, String detail) {

    public static ProcessProviderStatusResult applied(MessageId messageId, MessageStatus status) {
        return new ProcessProviderStatusResult(messageId, status, true, null);
    }

    public static ProcessProviderStatusResult ignored(MessageId messageId, MessageStatus status, String detail) {
        return new ProcessProviderStatusResult(messageId, status, false, detail);
    }

    /** No message carries the reported provider identifiers (late DLR, foreign traffic). */
    public static ProcessProviderStatusResult unknownMessage(String detail) {
        return new ProcessProviderStatusResult(null, null, false, detail);
    }

    public Optional<MessageId> messageIdOptional() {
        return Optional.ofNullable(messageId);
    }
}
