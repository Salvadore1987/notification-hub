package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON reader and writer of the FCM integration (§9.4.1).
 *
 * <p>Its own mapper, like every other contract boundary in this project: the shape FCM expects must not
 * move because another layer retuned its serialization.
 */
@Component
public class FcmJson {

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
            throw new IllegalStateException("Failed to serialize an FCM request (§9.4.1)", e);
        }
    }

    /** Parses an answer; {@code null} when the body is empty or not JSON. */
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

    /** Reads a field as text whatever its JSON type; empty when absent, null or structured. */
    public static Optional<String> scalar(JsonNode node, String field) {
        if (node == null) {
            return Optional.empty();
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isObject() || value.isArray()) {
            return Optional.empty();
        }
        String text = value.asString();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
