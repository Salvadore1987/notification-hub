package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** The published contract of PU-04: the field set and the schema resource must agree (NF-08). */
class PushTokenEventCodecTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final String SCHEMA_RESOURCE = "/schema/comm.outbound.push-token.invalidated.v1.json";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PushTokenEventCodec codec = new PushTokenEventCodec();

    @Test
    @DisplayName("PU-04: the token travels in the clear — the consumer's registry is keyed by it")
    void rendersTheEvent() {
        // Arrange
        PushTokenInvalidatedEvent event = event(ClientId.of("C123"));

        // Act
        JsonNode json = mapper.readTree(codec.write(event));

        // Assert
        assertThat(json.get("schemaVersion").asString()).isEqualTo(PushTokenEventCodec.SCHEMA_VERSION);
        assertThat(json.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-08-08T09:00:00Z");
        assertThat(json.get("streamId").asString()).isEqualTo("mobile-app");
        assertThat(json.get("clientId").asString()).isEqualTo("C123");
        assertThat(json.get("token").asString()).isEqualTo("device-a");
        assertThat(json.get("platform").asString()).isEqualTo("ANDROID");
        assertThat(json.get("provider").asString()).isEqualTo("FCM");
        assertThat(json.get("reason").asString()).isEqualTo("UNREGISTERED");
    }

    @Test
    @DisplayName("a submission without a client id still produces a complete document")
    void writesAnExplicitNullClient() {
        // Arrange + Act
        JsonNode json = mapper.readTree(codec.write(event(null)));

        // Assert
        assertThat(json.has("clientId")).isTrue();
        assertThat(json.get("clientId").isNull()).isTrue();
    }

    @Test
    @DisplayName("PU-04: invalidations of one customer stay in order — the key is the client")
    void keysByClient() {
        assertThat(codec.keyOf(event(ClientId.of("C123")))).isEqualTo("C123");
    }

    @Test
    @DisplayName("without a client, the token's hash keys the event — never the token itself")
    void keysByTokenHashWhenThereIsNoClient() {
        // Arrange
        PushTokenInvalidatedEvent event = event(null);

        // Act
        String key = codec.keyOf(event);

        // Assert
        assertThat(key).isEqualTo(event.tokenHash().value()).isNotEqualTo("device-a");
    }

    @Test
    @DisplayName("the registered schema and the serializer describe the same event (NF-08)")
    void schemaResourceMatchesTheSerializer() {
        // Arrange
        JsonNode schema = readSchema();

        // Act
        List<String> declared = new ArrayList<>();
        schema.get("properties").propertyNames().forEach(declared::add);
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        // Assert
        assertThat(declared).containsExactlyInAnyOrderElementsOf(PushTokenEventCodec.FIELDS);
        assertThat(required).containsExactlyInAnyOrderElementsOf(PushTokenEventCodec.FIELDS);
        assertThat(schema.get("properties").get("schemaVersion").get("const").asString())
                .isEqualTo(PushTokenEventCodec.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("OBS-03: the event never renders its token, whatever logs it")
    void neverPrintsTheToken() {
        assertThat(event(ClientId.of("C123")).toString())
                .doesNotContain("device-a")
                .contains("ANDROID");
    }

    private JsonNode readSchema() {
        try (InputStream schema = PushTokenEventCodecTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(schema).as("schema resource %s", SCHEMA_RESOURCE).isNotNull();
            return mapper.readTree(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + SCHEMA_RESOURCE, e);
        }
    }

    private static PushTokenInvalidatedEvent event(ClientId clientId) {
        return new PushTokenInvalidatedEvent(
                UuidV7.generate(),
                OCCURRED_AT,
                StreamId.of("mobile-app"),
                clientId,
                PushToken.of("device-a", PushPlatform.ANDROID),
                ProviderCode.of("FCM"),
                "UNREGISTERED");
    }
}
