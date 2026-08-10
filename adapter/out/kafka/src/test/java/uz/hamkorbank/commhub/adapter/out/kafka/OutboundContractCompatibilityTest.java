package uz.hamkorbank.commhub.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published outbound contracts, frozen (QA-04, NF-08, §8.1).
 *
 * <p>Each codec already has a test proving that what it writes matches the schema next to it. That pair
 * moves together: rename a field in both and every test still passes, while every consumer in the Bank
 * breaks on the next deployment.
 *
 * <p>So the field lists below are written out by hand rather than read from anywhere. They are the
 * promise the {@code v1} subjects carry — <b>BACKWARD</b> compatibility, which allows a new optional
 * field and forbids removing or renaming one. Editing a list here is the moment somebody decides
 * whether the change needs {@code v2} and a migration window for the consumers, and that decision should
 * cost a code review rather than an incident.
 *
 * <p>The status list of §6.3 is checked as a subset for the same reason and with the opposite polarity:
 * a new canonical status may be added, an existing one may not disappear from a document consumers
 * switch on.
 */
class OutboundContractCompatibilityTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** comm.outbound.status.v1 and comm.outbound.dlq.v1 — the transition stream of §6.4 (IK-02). */
    private static final List<String> STATUS_V1_FIELDS = List.of(
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

    /** comm.outbound.events.v1 — one row per finished message for the data mart (FR-6.4). */
    private static final List<String> EVENTS_V1_FIELDS = List.of(
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

    /** comm.outbound.push-token.invalidated.v1 — the device registry's contract (PU-04). */
    private static final List<String> PUSH_TOKEN_V1_FIELDS = List.of(
            "schemaVersion",
            "eventId",
            "occurredAt",
            "streamId",
            "clientId",
            "token",
            "platform",
            "provider",
            "reason");

    /** The canonical statuses of §6.3 that a consumer of the transition stream may be switching on. */
    private static final List<String> CANONICAL_STATUSES = List.of(
            "ACCEPTED",
            "VALIDATED",
            "ROUTED",
            "QUEUED",
            "SENDING",
            "SENT_TO_PROVIDER",
            "RETRYING",
            "DELIVERED",
            "UNDELIVERED",
            "EXPIRED",
            "REJECTED",
            "DUPLICATE",
            "CANCELLED",
            "FAILED");

    /**
     * The statuses the mart contract carries — the terminal ones of ST-02, plus {@code SENT_TO_PROVIDER}.
     *
     * <p>Deliberately not the full list: FR-6.4 exports one row per <em>finished</em> message, so an
     * intermediate status has nothing to do there, and {@code SENT_TO_PROVIDER} is on it because for push
     * that <em>is</em> the terminal status (PU-12).
     */
    private static final List<String> MART_STATUSES = List.of(
            "DELIVERED", "UNDELIVERED", "EXPIRED", "REJECTED", "DUPLICATE", "CANCELLED", "FAILED", "SENT_TO_PROVIDER");

    @Test
    @DisplayName("NF-08: comm.outbound.status.v1 still carries exactly the fields v1 promised")
    void statusContractIsUnchanged() {
        // Arrange
        JsonNode schema = read("comm.outbound.status.v1");

        // Act
        List<String> declared = propertyNames(schema.get("properties"));

        // Assert
        assertThat(declared).containsExactlyElementsOf(STATUS_V1_FIELDS);
        assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(STATUS_V1_FIELDS);
        assertThat(version(schema)).isEqualTo("1.0");
    }

    @Test
    @DisplayName("NF-08: comm.outbound.events.v1 still carries exactly the fields v1 promised")
    void martContractIsUnchanged() {
        // Arrange
        JsonNode schema = read("comm.outbound.events.v1");

        // Act
        List<String> declared = propertyNames(schema.get("properties"));

        // Assert
        assertThat(declared).containsExactlyElementsOf(EVENTS_V1_FIELDS);
        assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(EVENTS_V1_FIELDS);
        assertThat(version(schema)).isEqualTo("1.0");
    }

    @Test
    @DisplayName("NF-08: comm.outbound.push-token.invalidated.v1 still carries exactly the fields v1 promised")
    void pushTokenContractIsUnchanged() {
        // Arrange
        JsonNode schema = read("comm.outbound.push-token.invalidated.v1");

        // Act
        List<String> declared = propertyNames(schema.get("properties"));

        // Assert
        assertThat(declared).containsExactlyElementsOf(PUSH_TOKEN_V1_FIELDS);
        assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(PUSH_TOKEN_V1_FIELDS);
        assertThat(version(schema)).isEqualTo("1.0");
    }

    @Test
    @DisplayName("§6.3: no canonical status disappears from a contract consumers switch on")
    void canonicalStatusesAreOnlyEverAdded() {
        // Arrange
        JsonNode status = read("comm.outbound.status.v1");
        JsonNode mart = read("comm.outbound.events.v1");

        // Act
        List<String> inStatus = enumOf(status, "status");
        List<String> inMart = enumOf(mart, "status");

        // Assert
        assertThat(inStatus).containsAll(CANONICAL_STATUSES);
        assertThat(inMart).containsAll(MART_STATUSES);
    }

    @Test
    @DisplayName("a consumer that reads only the documented fields sees a closed object")
    void contractsRefuseUndeclaredFields() {
        // Arrange
        List<String> subjects = List.of(
                "comm.outbound.status.v1", "comm.outbound.events.v1", "comm.outbound.push-token.invalidated.v1");

        // Act + Assert — additionalProperties: false is what makes "the field list is the contract" true
        for (String subject : subjects) {
            assertThat(read(subject).get("additionalProperties").asBoolean())
                    .as("%s declares a closed object", subject)
                    .isFalse();
        }
    }

    private static String version(JsonNode schema) {
        return schema.get("properties").get("schemaVersion").get("const").asString();
    }

    private static List<String> required(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("required").forEach(node -> names.add(node.asString()));
        return names;
    }

    private static List<String> enumOf(JsonNode schema, String field) {
        List<String> values = new ArrayList<>();
        schema.get("properties").get(field).get("enum").forEach(node -> values.add(node.asString()));
        return values;
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static JsonNode read(String subject) {
        String resource = "/schema/" + subject + ".json";
        try (InputStream schema = OutboundContractCompatibilityTest.class.getResourceAsStream(resource)) {
            assertThat(schema).as("schema resource %s", resource).isNotNull();
            return MAPPER.readTree(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }
}
