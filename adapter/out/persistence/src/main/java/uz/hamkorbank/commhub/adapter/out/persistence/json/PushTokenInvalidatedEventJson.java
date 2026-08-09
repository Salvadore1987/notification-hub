package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.time.Instant;
import java.util.UUID;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * {@link PushTokenInvalidatedEvent} inside {@code outbox_event.payload} (PU-04).
 *
 * <p>Flat and in the wire shape, like {@link MessageStatusEventJson}: the relay publishes the stored
 * payload as it is, so the storage format is the published contract rather than an accident of the
 * record's field names.
 *
 * <p>The token is stored in the clear. It has to be — the consumer's device registry is keyed by the
 * token itself — and it is the one field of this payload that is worth stating explicitly: unlike
 * message content (DB-04), a revocation notice whose subject is encrypted names nothing.
 */
public record PushTokenInvalidatedEventJson(
        UUID eventId,
        String occurredAt,
        String streamId,
        String clientId,
        String token,
        String platform,
        String provider,
        String reason) {

    public static PushTokenInvalidatedEventJson of(PushTokenInvalidatedEvent event) {
        return new PushTokenInvalidatedEventJson(
                event.eventId(),
                event.occurredAt().toString(),
                event.streamId().value(),
                event.clientId() == null ? null : event.clientId().value(),
                event.token().value(),
                event.platform().name(),
                event.provider().value(),
                event.reason());
    }

    public PushTokenInvalidatedEvent toDomain() {
        return new PushTokenInvalidatedEvent(
                eventId,
                Instant.parse(occurredAt),
                StreamId.of(streamId),
                clientId == null ? null : ClientId.of(clientId),
                PushToken.of(token, PushPlatform.valueOf(platform)),
                ProviderCode.of(provider),
                reason);
    }
}
