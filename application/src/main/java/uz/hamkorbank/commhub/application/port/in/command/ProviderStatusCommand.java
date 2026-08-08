package uz.hamkorbank.commhub.application.port.in.command;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
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
 * @param suppressAs set when the report also says the address itself must not be used again — SMS Gate
 *     {@code InBlackList} (§18.2 code 7) or an email hard bounce (EM-02). It is a separate field on
 *     purpose: the canonical status describes what happened to <em>this</em> message, the suppression
 *     describes what happens to every next one, and §18.2 code 7 needs both at once
 */
public record ProviderStatusCommand(
        ProviderCode providerCode,
        ProviderMessageId providerMessageId,
        MessageId messageId,
        MessageStatus status,
        String providerStatus,
        String detail,
        SuppressionReason suppressAs,
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
                providerCode, providerMessageId, null, status, providerStatus, null, null, occurredAt);
    }

    /** The same report, with the address it names put on the suppression list (FR-5.1, EM-02, §18.2). */
    public ProviderStatusCommand suppressing(SuppressionReason reason) {
        return new ProviderStatusCommand(
                providerCode, providerMessageId, messageId, status, providerStatus, detail, reason, occurredAt);
    }

    public Optional<MessageId> messageIdOptional() {
        return Optional.ofNullable(messageId);
    }

    public Optional<ProviderMessageId> providerMessageIdOptional() {
        return Optional.ofNullable(providerMessageId);
    }

    public Optional<SuppressionReason> suppressAsOptional() {
        return Optional.ofNullable(suppressAs);
    }
}
