package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A device token the platform has declared dead (PU-04, PU-08).
 *
 * <p>Published to {@code comm.outbound.push-token.invalidated.v1} so that the system which registered
 * the token stops sending it. The Hub also stops on its own — the token goes onto the suppression list
 * — but that only protects the Hub: a source system that keeps the token keeps submitting messages
 * that are rejected before they reach a provider, and nobody upstream learns why.
 *
 * <p><b>The token travels in the clear</b>, unlike everything else the Hub publishes about a recipient.
 * It has to: the consumer's registry is keyed by the token itself, and a hash would name a row only the
 * Hub can find. A device token is not a customer identifier — it is revoked by the very event carrying
 * it — but it is still never written to a log (masked through {@link PushToken#masked()}).
 *
 * @param provider provider that reported it, so an operator can tell an APNs verdict from an FCM one
 * @param reason the platform's own word: {@code UNREGISTERED}, {@code BadDeviceToken}, {@code 410}
 */
public record PushTokenInvalidatedEvent(
        UUID eventId,
        Instant occurredAt,
        StreamId streamId,
        ClientId clientId,
        PushToken token,
        ProviderCode provider,
        String reason)
        implements OutboxPayload {

    public PushTokenInvalidatedEvent {
        Guard.notNull(eventId, "PushTokenInvalidatedEvent.eventId");
        Guard.notNull(occurredAt, "PushTokenInvalidatedEvent.occurredAt");
        Guard.notNull(streamId, "PushTokenInvalidatedEvent.streamId");
        Guard.notNull(token, "PushTokenInvalidatedEvent.token");
        Guard.notNull(provider, "PushTokenInvalidatedEvent.provider");
    }

    public PushPlatform platform() {
        return token.platform();
    }

    /** Hash of the token, which is how the same address is named in the suppression list (DB-04). */
    public AddressHash tokenHash() {
        return AddressHash.ofPushToken(token);
    }

    public Optional<ClientId> clientIdOptional() {
        return Optional.ofNullable(clientId);
    }

    /**
     * Partition key of the event: the client when the submission named one, the token hash otherwise.
     *
     * <p>Per client rather than per token so that a customer who reinstalled the application on two
     * devices has both invalidations delivered in order to the consumer that has to reconcile them.
     */
    public String aggregateId() {
        return clientId == null ? tokenHash().value() : clientId.value();
    }

    @Override
    public String toString() {
        return "PushTokenInvalidatedEvent[eventId=%s, token=%s, provider=%s, reason=%s]"
                .formatted(eventId, token.masked(), provider.value(), reason);
    }
}
