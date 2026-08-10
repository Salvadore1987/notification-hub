package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** The published contract of §6.4: the field set, the nulls and the schema resource must agree. */
class StatusEventCodecTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final String SCHEMA_RESOURCE = "/schema/comm.outbound.status.v1.json";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StatusEventCodec codec = new StatusEventCodec();

    @Test
    @DisplayName("a delivered SMS is rendered field for field as §6.4 prescribes")
    void rendersTheDeliveredEvent() {
        // Arrange
        MessageStatusEvent event = delivered();

        // Act
        JsonNode json = mapper.readTree(codec.write(event));

        // Assert
        assertThat(json.get("schemaVersion").asString()).isEqualTo(StatusEventCodec.SCHEMA_VERSION);
        assertThat(json.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-08-08T09:00:00Z");
        assertThat(json.get("streamId").asString()).isEqualTo("mobile-app");
        assertThat(json.get("messageId").asString())
                .isEqualTo(event.key().messageId().value().toString());
        assertThat(json.get("externalMessageId").asString()).isEqualTo("abc0000001");
        assertThat(json.get("channel").asString()).isEqualTo("SMS");
        assertThat(json.get("provider").asString()).isEqualTo("PLAYMOBILE");
        assertThat(json.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(json.get("providerStatus").asString()).isEqualTo("DLVRD");
        assertThat(json.get("segments").asInt()).isEqualTo(2);
        assertThat(json.get("correlationId").asString()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("absent values are explicit nulls, not missing fields")
    void writesAbsentValuesAsNulls() {
        // Arrange
        MessageStatusEvent event = rejectedBeforeRouting();

        // Act
        JsonNode json = mapper.readTree(codec.write(event));

        // Assert
        assertThat(json.get("batchId").isNull()).isTrue();
        assertThat(json.get("channel").isNull()).isTrue();
        assertThat(json.get("provider").isNull()).isTrue();
        assertThat(json.get("providerStatus").isNull()).isTrue();
        assertThat(fieldsOf(json)).containsExactlyElementsOf(StatusEventCodec.FIELDS);
    }

    @Test
    @DisplayName("a rejection carries its canonical code and its detail (IR-01)")
    void rendersTheRejectionReason() {
        // Arrange
        MessageStatusEvent event = rejectedBeforeRouting();

        // Act
        JsonNode reason = mapper.readTree(codec.write(event)).get("reason");

        // Assert
        assertThat(reason.get("code").asString()).isEqualTo("PAN_DETECTED");
        assertThat(reason.get("detail").asString()).isEqualTo("PAN found in the body");
    }

    @Test
    @DisplayName("a batch message carries its batch id")
    void rendersTheBatchId() {
        // Arrange
        BatchId batchId = BatchId.newId();

        // Act
        JsonNode json = mapper.readTree(codec.write(batched(batchId)));

        // Assert
        assertThat(json.get("batchId").asString()).isEqualTo(batchId.value().toString());
    }

    @Test
    @DisplayName("the partition key is the message id, so statuses of one message keep their order")
    void keysByMessageId() {
        // Arrange
        MessageStatusEvent event = delivered();

        // Act
        String key = codec.keyOf(event);

        // Assert
        assertThat(key).isEqualTo(event.key().messageId().value().toString());
    }

    @Test
    @DisplayName("the registered schema and the serializer describe the same event (NF-08)")
    void schemaResourceMatchesTheSerializer() {
        // Arrange
        JsonNode schema = readSchema();

        // Act
        List<String> declared = fieldsOf(schema.get("properties"));
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asString()));

        // Assert
        assertThat(declared).containsExactlyInAnyOrderElementsOf(StatusEventCodec.FIELDS);
        assertThat(required).containsExactlyInAnyOrderElementsOf(StatusEventCodec.FIELDS);
        assertThat(schema.get("properties").get("schemaVersion").get("const").asString())
                .isEqualTo(StatusEventCodec.SCHEMA_VERSION);
    }

    private JsonNode readSchema() {
        try (InputStream schema = StatusEventCodecTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(schema).as("schema resource %s", SCHEMA_RESOURCE).isNotNull();
            return mapper.readTree(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read " + SCHEMA_RESOURCE, e);
        }
    }

    private static List<String> fieldsOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static MessageStatusEvent delivered() {
        return new MessageStatusEvent(
                UuidV7.generate(),
                OCCURRED_AT,
                key(null),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                MessageStatus.DELIVERED,
                "DLVRD",
                null,
                2);
    }

    private static MessageStatusEvent batched(BatchId batchId) {
        return new MessageStatusEvent(
                UuidV7.generate(),
                OCCURRED_AT,
                key(batchId),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                MessageStatus.SENT_TO_PROVIDER,
                "ACCEPTD",
                null,
                1);
    }

    private static MessageStatusEvent rejectedBeforeRouting() {
        return new MessageStatusEvent(
                UuidV7.generate(),
                OCCURRED_AT,
                key(null),
                null,
                null,
                MessageStatus.REJECTED,
                null,
                MessageStatusEvent.StatusReason.of(RejectionReason.PAN_DETECTED, "PAN found in the body"),
                0);
    }

    private static MessageKey key(BatchId batchId) {
        return new MessageKey(
                StreamId.of("mobile-app"),
                batchId,
                MessageId.of(UUID.randomUUID()),
                ExternalMessageId.of("abc0000001"),
                CorrelationId.of("corr-1"));
    }
}
