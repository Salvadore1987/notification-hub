package uz.hamkorbank.commhub.adapter.out.kafka;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;

/**
 * Serializes a finished send for the data mart (FR-6.4).
 *
 * <p>Written field by field next to its schema resource, like the two codecs beside it: this is a
 * published contract, agreed with the data team, and a contract stays honest when the code that writes it
 * reads as the field list.
 *
 * <p>Flat rather than nested, unlike the record it comes from. A mart loads into columns, and a nested
 * {@code outcome} object would be unpacked again by whoever consumes it.
 *
 * <p>Money is written as an amount and a currency, never as a formatted string: the amount is summed
 * downstream and a locale-formatted number is a defect waiting for a report in another country.
 */
@Component
public class DeliveryEventCodec {

    /** Version of the mart contract; independent of the status one, which changes for its own reasons. */
    public static final String SCHEMA_VERSION = "1.0";

    /** The published field set; the schema resource must match it exactly. */
    public static final List<String> FIELDS = List.of(
            "schemaVersion",
            "messageId",
            "streamId",
            "batchId",
            "trafficClass",
            "channel",
            "provider",
            "status",
            "reason",
            "segments",
            "costAmount",
            "costCurrency",
            "attempts",
            "test",
            "acceptedAt",
            "terminalAt");

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(DeliveryEvent event) {
        ObjectNode json = mapper.createObjectNode();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("messageId", event.messageId().toString());
        json.put("streamId", event.streamId().value());
        json.put(
                "batchId",
                event.batchId() == null ? null : event.batchId().value().toString());
        json.put("trafficClass", event.trafficClass().name());
        json.put("channel", event.channel() == null ? null : event.channel().name());
        json.put("provider", event.provider() == null ? null : event.provider().value());
        writeOutcome(json, event);
        json.put("test", event.test());
        json.put("acceptedAt", event.outcome().acceptedAt().toString());
        json.put("terminalAt", event.outcome().terminalAt().toString());
        try {
            return mapper.writeValueAsString(json);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to serialize the delivery event of message %s (FR-6.4)".formatted(event.messageId()), e);
        }
    }

    private static void writeOutcome(ObjectNode json, DeliveryEvent event) {
        DeliveryEvent.DeliveryOutcome outcome = event.outcome();
        json.put("status", outcome.status().name());
        json.put("reason", outcome.reason() == null ? null : outcome.reason().name());
        json.put("segments", outcome.segments());
        json.put("costAmount", outcome.cost() == null ? null : outcome.cost().amount());
        json.put(
                "costCurrency",
                outcome.cost() == null ? null : outcome.cost().currency().getCurrencyCode());
        json.put("attempts", outcome.attempts());
    }
}
