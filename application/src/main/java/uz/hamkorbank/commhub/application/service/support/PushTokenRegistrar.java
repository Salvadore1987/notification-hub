package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * Retires a device token the platform has declared dead (PU-04, PU-08).
 *
 * <p>Two things have to happen and neither is optional. The Hub stops using the token — an entry in the
 * suppression list, keyed by the token's hash like every other address (FR-5.1, DB-04) — and the system
 * that registered the token is told, through {@code comm.outbound.push-token.invalidated.v1}. Only the
 * first protects this send; only the second stops the source system from resubmitting the same dead
 * device tomorrow, which for a bulk campaign is the difference between one wasted call and a month of
 * them.
 *
 * <p>Idempotent by {@code saveIfAbsent}, because the sources are not: a campaign hits the same retired
 * device once per message, and every one of those answers says {@code UNREGISTERED} again. Only a
 * genuinely new entry produces an event and a metric — an outbound topic that repeats the same
 * invalidation once per message of a batch is a topic nobody can consume.
 *
 * <p>The sibling of {@link SuppressionRegistrar}, kept apart from it because the outbound event is the
 * whole point here and an SMS blacklist has no equivalent: nobody outside the Hub can act on "this
 * number is on one provider's list", while a dead token is a row in somebody's device registry.
 */
@Component
public class PushTokenRegistrar {

    private final SuppressionRepository suppressions;
    private final OutboxPort outbox;
    private final MetricsPort metrics;

    public PushTokenRegistrar(SuppressionRepository suppressions, OutboxPort outbox, MetricsPort metrics) {
        this.suppressions = Guard.notNull(suppressions, "suppressions");
        this.outbox = Guard.notNull(outbox, "outbox");
        this.metrics = Guard.notNull(metrics, "metrics");
    }

    /**
     * Retires the token and, if it was not retired already, announces it.
     *
     * @param reason the platform's own word, kept verbatim for the event and the audit trail (PU-08)
     * @return {@code true} when this call is what retired the token
     */
    public boolean invalidate(
            Message message, ProviderRef provider, PushToken token, String reason, Instant respondedAt) {
        Guard.notNull(message, "message");
        Guard.notNull(provider, "provider");
        Guard.notNull(token, "token");
        Guard.notNull(respondedAt, "respondedAt");
        SuppressionEntry candidate = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(),
                Channel.PUSH,
                RecipientAddresses.of(token),
                SuppressionReason.PUSH_TOKEN_INVALID,
                respondedAt,
                Actor.provider(provider.code().value()).toString());
        SuppressionEntry inForce = suppressions.saveIfAbsent(candidate);
        if (!inForce.id().equals(candidate.id())) {
            return false;
        }
        metrics.recipientSuppressed(Channel.PUSH, SuppressionReason.PUSH_TOKEN_INVALID);
        outbox.append(OutboxEvent.pushTokenInvalidated(new PushTokenInvalidatedEvent(
                UuidV7.generate(),
                respondedAt,
                message.envelope().streamId(),
                message.recipient().clientId(),
                token,
                provider.code(),
                reason)));
        return true;
    }

    /** Whether the token is already retired and must be skipped by the fan-out (PU-04). */
    public boolean isRetired(PushToken token, Instant now) {
        Guard.notNull(token, "token");
        Optional<SuppressionEntry> entry =
                suppressions.findActiveByAddress(RecipientAddresses.of(token), Channel.PUSH, now);
        return entry.isPresent();
    }
}
