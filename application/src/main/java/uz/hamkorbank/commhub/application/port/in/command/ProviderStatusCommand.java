package uz.hamkorbank.commhub.application.port.in.command;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Delivery report of a provider, already translated to the canonical model (AD-06, ST-03, PM-02,
 * SG-02).
 *
 * <p>The mapping of the provider vocabulary onto {@link MessageStatus} lives in the adapter (§18.1,
 * §18.2); the use case only applies the transition and records the history entry (ST-01).
 *
 * @param messageId set when the callback carries the Hub identifier; otherwise the message is found
 *     by {@code (providerCode, providerMessageId)}
 * @param providerStatus raw provider status, kept in the history and forwarded to the source (§6.4)
 */
public record ProviderStatusCommand(
        ProviderCode providerCode,
        ProviderMessageId providerMessageId,
        MessageId messageId,
        MessageStatus status,
        String providerStatus,
        String detail,
        Instant occurredAt) {

    public ProviderStatusCommand {
        Guard.notNull(providerCode, "ProviderStatusCommand.providerCode");
        Guard.notNull(status, "ProviderStatusCommand.status");
        Guard.notNull(occurredAt, "ProviderStatusCommand.occurredAt");
        Guard.isTrue(
                providerMessageId != null || messageId != null,
                "ProviderStatusCommand requires a providerMessageId or a messageId");
    }

    public static ProviderStatusCommand of(
            ProviderCode providerCode,
            ProviderMessageId providerMessageId,
            MessageStatus status,
            String providerStatus,
            Instant occurredAt) {
        return new ProviderStatusCommand(
                providerCode, providerMessageId, null, status, providerStatus, null, occurredAt);
    }

    public Optional<MessageId> messageIdOptional() {
        return Optional.ofNullable(messageId);
    }

    public Optional<ProviderMessageId> providerMessageIdOptional() {
        return Optional.ofNullable(providerMessageId);
    }
}
