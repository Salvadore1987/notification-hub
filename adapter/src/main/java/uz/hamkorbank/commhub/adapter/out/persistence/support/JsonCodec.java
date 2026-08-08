package uz.hamkorbank.commhub.adapter.out.persistence.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the {@code jsonb} columns of the schema (§10.1).
 *
 * <p>The mapper is private to persistence and deliberately not the Spring Boot one: stored JSON is a
 * long-lived format, and it must not change shape because the REST layer retunes its serialization.
 * Unknown properties are ignored so a column written by a newer version still loads after a rollback,
 * and nulls are dropped so absent optionals do not bloat every row.
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Serializes a payload for a {@code jsonb} column; {@code null} stays {@code null}. */
    public String write(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new DataRetrievalFailureException(
                    "Failed to serialize %s for a jsonb column"
                            .formatted(payload.getClass().getName()),
                    e);
        }
    }

    /** Deserializes a {@code jsonb} column; a {@code null} or blank column yields {@code null}. */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new DataRetrievalFailureException(
                    "Failed to deserialize a jsonb column into %s".formatted(type.getName()), e);
        }
    }

    /** Deserializes a {@code jsonb} object of string values, e.g. merge variables of a template (FR-4.3). */
    public Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(
                    json, mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (JacksonException e) {
            throw new DataRetrievalFailureException("Failed to deserialize a jsonb column into a map", e);
        }
    }

    /** Deserializes a {@code jsonb} array; a {@code null} or blank column yields an empty list. */
    public <T> List<T> readList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JacksonException e) {
            throw new DataRetrievalFailureException(
                    "Failed to deserialize a jsonb column into a list of %s".formatted(elementType.getName()), e);
        }
    }
}
