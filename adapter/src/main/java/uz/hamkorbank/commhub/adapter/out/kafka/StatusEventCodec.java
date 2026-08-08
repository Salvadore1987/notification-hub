package uz.hamkorbank.commhub.adapter.out.kafka;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/**
 * Serializes a {@link MessageStatusEvent} into the wire format of §6.4.
 *
 * <p>Written field by field instead of through a mapped record for two reasons. §6.4 is flat and has
 * thirteen fields, past the eight this project allows a record to carry; and the contract published to
 * the source systems should be readable as a contract — this class is the field list of §6.4 in the
 * order the specification prints it, next to the schema in
 * {@code resources/schema/comm.outbound.status.v1.json} that operations registers in the Schema
 * Registry (NF-08).
 *
 * <p>Absent values are written as explicit nulls, as the specification shows them: a consumer parsing
 * the event sees the same field set for every message, whatever happened to it.
 *
 * <p>Its own {@link JsonMapper}, not the REST one and not the persistence one: a topic is the slowest
 * contract in the system to change, and it must not move because another layer retuned its
 * serialization. Compatibility is BACKWARD (NF-08) — fields may be added, never renamed or removed.
 */
@Component
public class StatusEventCodec {

    /** Version of the outbound status contract; mirrors {@code schemaVersion} of the inbound one (IK-03). */
    public static final String SCHEMA_VERSION = "1.0";

    /** The published field set, in the order of §6.4; the schema resource must match it exactly. */
    public static final List<String> FIELDS = List.of(
            "schemaVersion",
            "eventId",
            "occurredAt",
            "streamId",
            "batchId",
            "messageId",
            "externalMessageId",
            "channel",
            "provider",
            "status",
            "providerStatus",
            "reason",
            "segments",
            "correlationId");

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** Renders the event as the JSON document of §6.4. */
    public String write(MessageStatusEvent event) {
        ObjectNode json = mapper.createObjectNode();
        MessageKey key = event.key();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("eventId", event.eventId().toString());
        json.put("occurredAt", event.occurredAt().toString());
        json.put("streamId", key.streamId().value());
        json.put("batchId", key.batchId() == null ? null : key.batchId().value().toString());
        json.put("messageId", key.messageId().value().toString());
        json.put("externalMessageId", key.externalMessageId().value());
        json.put("channel", event.channelOptional().map(Enum::name).orElse(null));
        json.put("provider", event.providerOptional().map(ProviderCode::value).orElse(null));
        json.put("status", event.status().name());
        json.put("providerStatus", event.providerStatus());
        writeReason(json, event);
        json.put("segments", event.segments());
        json.put("correlationId", key.correlationId().value());
        try {
            return mapper.writeValueAsString(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to serialize the status event %s (§6.4)".formatted(event.eventId()), e);
        }
    }

    /** Partition key of the event: statuses of one message must stay in order for its consumer. */
    public String keyOf(MessageStatusEvent event) {
        return event.key().messageId().value().toString();
    }

    /** {@code null} when the message is on its happy path, {@code {code, detail}} when it is not (IR-01). */
    private static void writeReason(ObjectNode json, MessageStatusEvent event) {
        MessageStatusEvent.StatusReason reason = event.reasonOptional().orElse(null);
        if (reason == null) {
            json.putNull("reason");
            return;
        }
        ObjectNode node = json.putObject("reason");
        node.put("code", reason.code().name());
        node.put("detail", reason.detail());
    }
}
