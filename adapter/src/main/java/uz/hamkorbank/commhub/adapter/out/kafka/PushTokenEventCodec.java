package uz.hamkorbank.commhub.adapter.out.kafka;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;

/**
 * Serializes a {@link PushTokenInvalidatedEvent} for {@code comm.outbound.push-token.invalidated.v1}
 * (PU-04, PU-08).
 *
 * <p>Field by field and next to its schema resource, exactly like {@link StatusEventCodec}, and for the
 * same reason: this is a published contract, and a contract is easier to keep honest when the code that
 * writes it reads as the field list.
 *
 * <p>The document is deliberately small. Its consumer owns a device registry, not a message log — it
 * needs to know which token to delete and enough to trust the instruction, and nothing about the
 * notification that happened to discover it.
 */
@Component
public class PushTokenEventCodec {

    /** Version of the contract; independent of the status one, which changes for its own reasons. */
    public static final String SCHEMA_VERSION = "1.0";

    /** The published field set; the schema resource must match it exactly. */
    public static final List<String> FIELDS = List.of(
            "schemaVersion",
            "eventId",
            "occurredAt",
            "streamId",
            "clientId",
            "token",
            "platform",
            "provider",
            "reason");

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(PushTokenInvalidatedEvent event) {
        ObjectNode json = mapper.createObjectNode();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("eventId", event.eventId().toString());
        json.put("occurredAt", event.occurredAt().toString());
        json.put("streamId", event.streamId().value());
        json.put("clientId", event.clientId() == null ? null : event.clientId().value());
        json.put("token", event.token().value());
        json.put("platform", event.platform().name());
        json.put("provider", event.provider().value());
        json.put("reason", event.reason());
        try {
            return mapper.writeValueAsString(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to serialize the push token event %s (PU-04)".formatted(event.eventId()), e);
        }
    }

    /**
     * Partition key: the client, or the token's hash when the submission named no client.
     *
     * <p>Per customer rather than per token so that two devices retired in the same minute reach the
     * registry in the order they were retired — a consumer that reconciles them one partition apart
     * cannot tell.
     */
    public String keyOf(PushTokenInvalidatedEvent event) {
        return event.aggregateId();
    }
}
