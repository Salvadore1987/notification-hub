package uz.hamkorbank.commhub.adapter.in.contract;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reader of the inbound documents (IK-03, §8.2).
 *
 * <p>Its own {@link JsonMapper}, like the outbound status codec and the persistence codec have theirs:
 * the contract of the source systems must not move because another layer retuned its serialization.
 *
 * <p>Unknown fields are ignored on purpose. IK-03 is versioned backward-compatibly (§8.1), so a source
 * system that has already adopted a newer schema keeps working against an instance that has not been
 * upgraded yet — the alternative is an outage on the day a field is added.
 */
@Component
public class InboundJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Parses the document into a tree, reporting an unreadable body as a contract violation. */
    public JsonNode read(String document) {
        if (document == null || document.isBlank()) {
            throw InboundContractException.missing("body");
        }
        try {
            JsonNode root = mapper.readTree(document);
            if (!root.isObject()) {
                throw InboundContractException.invalid("body", "must be a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            throw InboundContractException.invalid("body", "is not valid JSON", e);
        }
    }

    /** Binds one nested block of the document onto its payload record. */
    public <T> T convert(JsonNode node, Class<T> type, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw InboundContractException.invalid(field, "has an unexpected shape", e);
        }
    }

    /** Binds a whole document onto its payload record; used where the root fits into one record. */
    public <T> T readValue(String document, Class<T> type) {
        return convert(read(document), type, "body");
    }
}
