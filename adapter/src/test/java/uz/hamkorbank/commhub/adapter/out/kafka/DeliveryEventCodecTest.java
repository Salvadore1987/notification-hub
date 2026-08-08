package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent.DeliveryOutcome;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** The published contract of FR-6.4: the field set and the schema resource must agree (NF-08). */
class DeliveryEventCodecTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant TERMINAL_AT = Instant.parse("2026-08-08T09:00:04Z");
    private static final String SCHEMA_RESOURCE = "/schema/comm.outbound.events.v1.json";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final DeliveryEventCodec codec = new DeliveryEventCodec();

    @Test
    @DisplayName("a delivered message is rendered flat, with cost as an amount and a currency")
    void rendersADeliveredMessage() {
        // Arrange
        DeliveryEvent event = delivered();

        // Act
        JsonNode json = mapper.readTree(codec.write(event));

        // Assert
        assertThat(json.get("schemaVersion").asString()).isEqualTo(DeliveryEventCodec.SCHEMA_VERSION);
        assertThat(json.get("messageId").asString()).isEqualTo(event.messageId().toString());
        assertThat(json.get("streamId").asString()).isEqualTo("chakana");
        assertThat(json.get("trafficClass").asString()).isEqualTo("TRANSACTIONAL");
        assertThat(json.get("channel").asString()).isEqualTo("SMS");
        assertThat(json.get("provider").asString()).isEqualTo("PLAYMOBILE");
        assertThat(json.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(json.get("segments").asInt()).isEqualTo(2);
        assertThat(json.get("costAmount").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(json.get("costCurrency").asString()).isEqualTo("UZS");
        assertThat(json.get("attempts").asInt()).isEqualTo(1);
        assertThat(json.get("acceptedAt").asString()).isEqualTo("2026-08-08T09:00:00Z");
        assertThat(json.get("terminalAt").asString()).isEqualTo("2026-08-08T09:00:04Z");
        assertThat(fieldsOf(json)).containsExactlyElementsOf(DeliveryEventCodec.FIELDS);
    }

    @Test
    @DisplayName("SEC-06: neither the recipient nor the content leaves for the analytical contour")
    void carriesNoPersonalData() {
        // Arrange + Act
        String document = codec.write(delivered());

        // Assert
        assertThat(document).doesNotContain("recipient").doesNotContain("998").doesNotContain("text");
    }

    @Test
    @DisplayName("FR-7.4: the test flag travels so the mart can exclude configuration checks")
    void carriesTheTestFlag() {
        // Arrange
        DeliveryEvent test = new DeliveryEvent(
                MessageId.newId(),
                StreamId.of("chakana"),
                null,
                TrafficClass.TRANSACTIONAL,
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                new DeliveryOutcome(MessageStatus.DELIVERED, null, 1, null, 1, ACCEPTED_AT, TERMINAL_AT),
                true);

        // Act + Assert
        assertThat(mapper.readTree(codec.write(test)).get("test").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a message rejected before routing keeps its reason and explicit nulls")
    void rendersARejectedMessage() {
        // Arrange
        DeliveryEvent rejected = new DeliveryEvent(
                MessageId.newId(),
                StreamId.of("chakana"),
                null,
                TrafficClass.NOTIFICATION,
                null,
                null,
                new DeliveryOutcome(
                        MessageStatus.REJECTED, RejectionReason.SUPPRESSED, 0, null, 0, ACCEPTED_AT, TERMINAL_AT),
                false);

        // Act
        JsonNode json = mapper.readTree(codec.write(rejected));

        // Assert
        assertThat(json.get("channel").isNull()).isTrue();
        assertThat(json.get("provider").isNull()).isTrue();
        assertThat(json.get("costAmount").isNull()).isTrue();
        assertThat(json.get("reason").asString()).isEqualTo("SUPPRESSED");
        assertThat(fieldsOf(json)).containsExactlyElementsOf(DeliveryEventCodec.FIELDS);
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
        assertThat(declared).containsExactlyInAnyOrderElementsOf(DeliveryEventCodec.FIELDS);
        assertThat(required).containsExactlyInAnyOrderElementsOf(DeliveryEventCodec.FIELDS);
        assertThat(schema.get("properties").get("schemaVersion").get("const").asString())
                .isEqualTo(DeliveryEventCodec.SCHEMA_VERSION);
    }

    private static DeliveryEvent delivered() {
        return new DeliveryEvent(
                MessageId.newId(),
                StreamId.of("chakana"),
                BatchId.newId(),
                TrafficClass.TRANSACTIONAL,
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                new DeliveryOutcome(
                        MessageStatus.DELIVERED,
                        null,
                        2,
                        new Money(new BigDecimal("120.00"), Currency.getInstance("UZS")),
                        1,
                        ACCEPTED_AT,
                        TERMINAL_AT),
                false);
    }

    private static List<String> fieldsOf(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.propertyNames().forEach(names::add);
        return names;
    }

    private JsonNode readSchema() {
        try (InputStream schema = DeliveryEventCodecTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(schema).as("schema resource %s", SCHEMA_RESOURCE).isNotNull();
            return mapper.readTree(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
