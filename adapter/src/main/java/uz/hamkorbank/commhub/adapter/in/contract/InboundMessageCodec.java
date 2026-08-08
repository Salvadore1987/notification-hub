package uz.hamkorbank.commhub.adapter.in.contract;

import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.in.contract.dto.ChannelsPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.ContentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.RecipientPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.TemplatePayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.TimingPayload;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapper;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads the inbound message document of IK-03 into a {@link SubmitMessageCommand} (AR-06).
 *
 * <p>The thirteen fields of IK-03 are read one by one rather than bound onto a record, for the reason
 * given in the package documentation: the flat root is past the eight components a record may carry
 * here. This class is therefore the field list of IK-03 in the order the specification prints it —
 * the inbound mirror of {@code StatusEventCodec}, which does the same for the outbound §6.4 event.
 *
 * <p>{@code schemaVersion} is accepted and checked for a major version this build understands, not
 * stored: what the Hub keeps is the message, and the version of the envelope it arrived in stops
 * being interesting the moment the command is built.
 */
@Component
public class InboundMessageCodec {

    /** Major version of IK-03 this build implements; a document of another major is refused. */
    public static final String SCHEMA_MAJOR_VERSION = "1";

    private final InboundJson json;
    private final InboundPayloadMapper mapper;

    public InboundMessageCodec(InboundJson json, InboundPayloadMapper mapper) {
        this.json = Guard.notNull(json, "json");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** Reads the document a source system sent over REST or Kafka. */
    public SubmitMessageCommand read(String document) {
        return read(json.read(document));
    }

    /** Reads an already-parsed document; the Kafka consumers reuse the tree for their logging. */
    public SubmitMessageCommand read(JsonNode root) {
        Guard.notNull(root, "root");
        requireSupportedSchema(text(root, "schemaVersion"));

        StreamId streamId = required(text(root, "streamId"), "streamId", StreamId::of);
        ExternalMessageId externalMessageId =
                required(text(root, "externalMessageId"), "externalMessageId", ExternalMessageId::of);
        BatchId batchId = optional(text(root, "batchId"), "batchId", BatchId::fromString);

        RecipientPayload recipient = json.convert(root.get("recipient"), RecipientPayload.class, "recipient");
        ContentPayload content = json.convert(root.get("content"), ContentPayload.class, "content");
        ChannelsPayload channels = json.convert(root.get("channels"), ChannelsPayload.class, "channels");
        TemplatePayload template = json.convert(root.get("template"), TemplatePayload.class, "template");
        TimingPayload timing = json.convert(root.get("timing"), TimingPayload.class, "timing");

        MessageContents contents = mapper.toContents(content, "content");
        TemplateRef templateRef = mapper.toTemplate(template, "template");
        requireContentOrTemplate(contents, templateRef);
        return new SubmitMessageCommand(
                streamId,
                externalMessageId,
                batchId,
                mapper.toRecipient(recipient, "recipient"),
                contents,
                mapper.toChannelPlan(channels, "channels"),
                templateRef,
                delivery(root, timing));
    }

    /**
     * Reads a document that arrived on a topic of a fixed traffic class (§8.1 IK-01).
     *
     * <p>The topic wins over the field: a message on {@code comm.inbound.critical.v1} is critical
     * whatever it claims to be, because the isolation of TC-01 is built from the topics and a payload
     * that could re-label itself would walk straight out of the OTP pool it was placed in.
     */
    public SubmitMessageCommand read(String document, TrafficClass trafficClass) {
        return read(json.read(document), trafficClass);
    }

    /** As above, on an already-parsed document. */
    public SubmitMessageCommand read(JsonNode root, TrafficClass trafficClass) {
        Guard.notNull(trafficClass, "trafficClass");
        SubmitMessageCommand command = read(root);
        SubmitMessageCommand.Delivery delivery = command.delivery();
        return new SubmitMessageCommand(
                command.streamId(),
                command.externalMessageId(),
                command.batchId(),
                command.recipient(),
                command.contents(),
                command.channelPlan(),
                command.template(),
                new SubmitMessageCommand.Delivery(
                        trafficClass,
                        delivery.priority(),
                        delivery.timing(),
                        delivery.dedupKey(),
                        delivery.correlationId(),
                        delivery.test()));
    }

    private SubmitMessageCommand.Delivery delivery(JsonNode root, TimingPayload timing) {
        return new SubmitMessageCommand.Delivery(
                enumOrNull(TrafficClass.class, text(root, "trafficClass"), "trafficClass"),
                enumOrNull(Priority.class, text(root, "priority"), "priority"),
                mapper.toTiming(timing, "timing"),
                optional(text(root, "dedupKey"), "dedupKey", DedupKey::of),
                optional(text(root, "correlationId"), "correlationId", CorrelationId::of),
                root.path("test").asBoolean(false));
    }

    private static void requireSupportedSchema(String schemaVersion) {
        if (InboundPayloadMapper.blank(schemaVersion)) {
            return;
        }
        String major = schemaVersion.split("\\.", 2)[0].trim();
        if (!SCHEMA_MAJOR_VERSION.equals(major)) {
            throw InboundContractException.invalid(
                    "schemaVersion",
                    "unsupported version %s, this build reads %s.x".formatted(schemaVersion, SCHEMA_MAJOR_VERSION));
        }
    }

    /**
     * FR-1.2: a message carries content, a template reference, or both.
     *
     * <p>Checked before the command is built, not after: the command enforces the same rule, but it
     * raises a domain rejection that says nothing about which field of the document was expected.
     */
    private static void requireContentOrTemplate(MessageContents contents, TemplateRef template) {
        if (contents == null && template == null) {
            throw InboundContractException.invalid("content", "either content or template is required (FR-1.2)");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private static <T> T required(String value, String field, Function<String, T> factory) {
        if (InboundPayloadMapper.blank(value)) {
            throw InboundContractException.missing(field);
        }
        return convert(value, field, factory);
    }

    private static <T> T optional(String value, String field, Function<String, T> factory) {
        return InboundPayloadMapper.blank(value) ? null : convert(value, field, factory);
    }

    private static <T> T convert(String value, String field, Function<String, T> factory) {
        try {
            return factory.apply(value.trim());
        } catch (RuntimeException e) {
            throw InboundContractException.invalid(field, e.getMessage(), e);
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw, String field) {
        return InboundPayloadMapper.blank(raw) ? null : InboundPayloadMapper.enumValue(type, raw, field);
    }

    /** The field set of IK-03, in the order of §8.1; kept next to the reader that consumes it. */
    public static List<String> fields() {
        return List.of(
                "schemaVersion",
                "streamId",
                "externalMessageId",
                "batchId",
                "trafficClass",
                "priority",
                "recipient",
                "channels",
                "content",
                "template",
                "timing",
                "dedupKey",
                "correlationId");
    }
}
