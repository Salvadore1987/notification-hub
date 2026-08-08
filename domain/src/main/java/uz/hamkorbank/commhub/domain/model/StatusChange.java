package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One entry of the message status history (ST-01, §10.1 {@code message_status_history}).
 *
 * @param reason canonical reason for non-delivery statuses; {@code null} otherwise
 * @param details free-form detail, e.g. the raw provider status or error description (PR-03)
 * @param providerCode provider that caused the change; {@code null} for internal transitions
 */
public record StatusChange(
        MessageStatus status,
        RejectionReason reason,
        String details,
        Actor actor,
        ProviderCode providerCode,
        Instant occurredAt) {

    public static final int MAX_DETAILS_LENGTH = 1024;

    public StatusChange {
        Guard.notNull(status, "StatusChange.status");
        Guard.notNull(actor, "StatusChange.actor");
        Guard.notNull(occurredAt, "StatusChange.occurredAt");
        Guard.maxLength(details, MAX_DETAILS_LENGTH, "StatusChange.details");
    }

    public Optional<RejectionReason> reasonOptional() {
        return Optional.ofNullable(reason);
    }

    public Optional<String> detailsOptional() {
        return Optional.ofNullable(details);
    }

    public Optional<ProviderCode> providerCodeOptional() {
        return Optional.ofNullable(providerCode);
    }
}
