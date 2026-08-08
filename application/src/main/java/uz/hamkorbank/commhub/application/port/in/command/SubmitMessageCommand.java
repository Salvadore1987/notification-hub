package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Submission of one message, the command form of the inbound contract IK-03 / §8.2
 * {@code POST /messages} (FR-1.1, FR-1.2).
 *
 * @param batchId {@code null} for a single message; set for an item of a batch (FR-1.6)
 * @param contents content per channel; {@code null} is allowed only together with {@link #template()},
 *     in which case the body of the published template becomes the content (FR-1.2, FR-4.3)
 * @param channelPlan requested channels; {@code null} lets the Hub choose (MP-03)
 * @param template template reference with its merge variables; {@code null} for ready-made content
 */
public record SubmitMessageCommand(
        StreamId streamId,
        ExternalMessageId externalMessageId,
        BatchId batchId,
        Recipient recipient,
        MessageContents contents,
        ChannelPlan channelPlan,
        TemplateRef template,
        Delivery delivery) {

    public SubmitMessageCommand {
        Guard.notNull(streamId, "SubmitMessageCommand.streamId");
        Guard.notNull(externalMessageId, "SubmitMessageCommand.externalMessageId");
        Guard.notNull(recipient, "SubmitMessageCommand.recipient");
        Guard.notNull(delivery, "SubmitMessageCommand.delivery");
        Guard.isTrue(
                contents != null || template != null,
                "SubmitMessageCommand requires content, a template reference or both (FR-1.2)");
    }

    /** Single message with ready-made content and the defaults of its stream. */
    public static SubmitMessageCommand of(
            StreamId streamId, ExternalMessageId externalMessageId, Recipient recipient, MessageContents contents) {
        return new SubmitMessageCommand(
                streamId, externalMessageId, null, recipient, contents, null, null, Delivery.defaults());
    }

    /** Returns a copy bound to a batch, used when a chunk of batch items is expanded (FR-1.6). */
    public SubmitMessageCommand inBatch(BatchId newBatchId) {
        return new SubmitMessageCommand(
                streamId, externalMessageId, newBatchId, recipient, contents, channelPlan, template, delivery);
    }

    public Optional<MessageContents> contentsOptional() {
        return Optional.ofNullable(contents);
    }

    public Optional<ChannelPlan> channelPlanOptional() {
        return Optional.ofNullable(channelPlan);
    }

    public Optional<TemplateRef> templateOptional() {
        return Optional.ofNullable(template);
    }

    /**
     * Delivery attributes of the envelope; every field may be omitted and is then taken from the
     * stream defaults or from the traffic class (TC-02, FR-2.4).
     *
     * @param dedupKey explicit idempotency key; {@code null} derives it from
     *     {@code (streamId, externalMessageId)} (FR-1.5)
     * @param correlationId trace identifier propagated by the caller; {@code null} generates one (FR-8.6)
     * @param test test send: delivered normally but excluded from business statistics (FR-7.4)
     */
    public record Delivery(
            TrafficClass trafficClass,
            Priority priority,
            Timing timing,
            DedupKey dedupKey,
            CorrelationId correlationId,
            boolean test) {

        private static final Delivery DEFAULTS = new Delivery(null, null, null, null, null, false);

        public static Delivery defaults() {
            return DEFAULTS;
        }

        public static Delivery of(TrafficClass trafficClass, Timing timing) {
            return new Delivery(trafficClass, null, timing, null, null, false);
        }

        public Optional<Timing> timingOptional() {
            return Optional.ofNullable(timing);
        }

        public Optional<DedupKey> dedupKeyOptional() {
            return Optional.ofNullable(dedupKey);
        }

        public Optional<CorrelationId> correlationIdOptional() {
            return Optional.ofNullable(correlationId);
        }
    }
}
