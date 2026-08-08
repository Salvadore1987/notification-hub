package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON reader and writer of the SMS Gate integration (§9.2).
 *
 * <p>Its own mapper, for the same reason every other contract boundary here has one.
 *
 * <p>{@link #scalar(JsonNode, String)} reads a field as text whatever its JSON type. SMS Gate returns
 * {@code id} and {@code code} sometimes as numbers and sometimes as strings depending on the endpoint,
 * and the Hub stores both as text anyway — a {@code ProviderMessageId} is an identifier, not a number.
 */
@Component
public class SmsGateJson {

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
            throw new IllegalStateException("Failed to serialize an SMS Gate request (§9.2)", e);
        }
    }

    /** Parses an answer or a callback; {@code null} when the body is empty or not JSON. */
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

    /** Reads a field as text regardless of whether it arrived as a number, a string or a boolean. */
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
