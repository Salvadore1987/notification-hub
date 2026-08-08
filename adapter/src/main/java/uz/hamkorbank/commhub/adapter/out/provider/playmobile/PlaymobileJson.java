package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON reader and writer of the Playmobile integration (§9.1).
 *
 * <p>Its own mapper, like every other contract boundary in this project has its own: the shape of
 * {@code /send} is Playmobile's to change, and it must not move because the persistence layer or the
 * source-system API retuned their serialization.
 *
 * <p>Unknown fields are ignored when reading. Playmobile adds fields to its answers and to its delivery
 * reports on its own schedule, and an integration that fails on the day they do is an outage the Bank
 * did not schedule.
 */
@Component
public class PlaymobileJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize a Playmobile request (§9.1)", e);
        }
    }

    /** Parses an answer or a callback; {@code null} when the body is empty or not JSON at all. */
    public JsonNode readOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }
}
