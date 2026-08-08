package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.mapper.MessageMapper;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.service.pipeline.FilterVerdict;
import uz.hamkorbank.commhub.application.service.pipeline.MessagePipeline;
import uz.hamkorbank.commhub.application.service.pipeline.PipelineVerdict;
import uz.hamkorbank.commhub.application.service.pipeline.TemplateOutcome;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.application.service.support.RoutingOutcome;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.service.RoutingResult;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Accepts one message from a source system (FR-1.1, FR-1.4, FR-1.5).
 *
 * <p>Walks the head of the pipeline in the order of SRS §5.1 — stream and kill switch → dedup →
 * template → validation → dedup claim → routing → filters → quotas — and leaves the message in the
 * status the sending saga picks up: {@code QUEUED} normally, {@code ROUTED} while a send window or a
 * quiet-hours window is still closed (FR-5.3, FR-8.5).
 *
 * <p>Everything happens in one transaction together with the outbox event, so a crash can neither
 * lose an accepted message nor a status update (AD-03).
 */
@Service
public class SubmitMessageService implements SubmitMessage {

    private final ClockPort clock;
    private final StreamRepository streams;
    private final MessageRepository messages;
    private final KillSwitchPort killSwitch;
    private final MessagePipeline pipeline;
    private final MessageStatusNotifier notifier;
    private final MessageMapper mapper;

    public SubmitMessageService(
            ClockPort clock,
            StreamRepository streams,
            MessageRepository messages,
            KillSwitchPort killSwitch,
            MessagePipeline pipeline,
            MessageStatusNotifier notifier,
            MessageMapper mapper) {
        this.clock = Guard.notNull(clock, "clock");
        this.streams = Guard.notNull(streams, "streams");
        this.messages = Guard.notNull(messages, "messages");
        this.killSwitch = Guard.notNull(killSwitch, "killSwitch");
        this.pipeline = Guard.notNull(pipeline, "pipeline");
        this.notifier = Guard.notNull(notifier, "notifier");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public SubmitMessageResult submit(SubmitMessageCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Optional<Stream> source = streams.findById(command.streamId());
        if (source.isEmpty()) {
            return refuse(command, RejectionReason.VALIDATION_FAILED, "unknown stream " + command.streamId());
        }
        Stream stream = source.get();
        if (!stream.isAcceptingTraffic()) {
            return refuse(command, RejectionReason.STREAM_SUSPENDED, "stream is " + stream.status());
        }
        TrafficClass trafficClass =
                stream.effectiveTrafficClass(command.delivery().trafficClass());
        if (killSwitch.state().stops(trafficClass)) {
            return refuse(command, RejectionReason.KILL_SWITCH, "sending is stopped by the kill switch");
        }

        DedupKey dedupKey =
                pipeline.dedupKeyOf(command.delivery().dedupKey(), command.streamId(), command.externalMessageId());
        Optional<MessageId> original = pipeline.findDuplicate(dedupKey, now);
        if (original.isPresent()) {
            return duplicate(command, original.get());
        }

        TemplateOutcome template = pipeline.applyTemplate(command.contents(), command.template(), null);
        if (template.isRejected()) {
            return refuse(
                    command, template.verdict().reason(), template.verdict().detail());
        }
        return accept(command, stream, trafficClass, template.contents(), dedupKey, now);
    }

    /** Builds the aggregate and runs the stages that need it (FR-1.4, MP-05, FR-5.1…FR-5.4, FR-2.6). */
    private SubmitMessageResult accept(
            SubmitMessageCommand command,
            Stream stream,
            TrafficClass trafficClass,
            MessageContents contents,
            DedupKey dedupKey,
            Instant now) {
        Message message;
        try {
            message = build(command, stream, trafficClass, contents, dedupKey, now);
        } catch (DomainValidationException e) {
            return refuse(command, RejectionReason.VALIDATION_FAILED, e.getMessage());
        }

        PipelineVerdict validation = pipeline.validate(message);
        if (validation.isRejected()) {
            return reject(message, validation.reason(), validation.detail(), now);
        }
        message.markValidated(Actor.system(), now);

        Optional<MessageId> owner = pipeline.claimDedupKey(dedupKey, message.id(), now);
        if (owner.isPresent()) {
            return duplicate(command, owner.get());
        }
        return route(message, stream, now);
    }

    /** Chooses the route, applies the filters and quotas, and queues the message (FR-2.2, FR-5.3). */
    private SubmitMessageResult route(Message message, Stream stream, Instant now) {
        RoutingOutcome outcome = pipeline.route(message, Set.of());
        if (!outcome.isRouted()) {
            RoutingResult.NoRoute noRoute = outcome.noRoute().orElseThrow();
            return reject(message, noRoute.reason(), noRoute.detail(), now);
        }
        RoutingResult.Routed routed = outcome.requireRouted();
        Channel channel = routed.channel();

        FilterVerdict filter = pipeline.filter(
                message, channel, stream, outcome.channelConfig(channel).orElse(null), now);
        if (filter.isRejected()) {
            return reject(message, filter.reason(), filter.detail(), now);
        }

        Optional<Money> cost = pipeline.costOf(outcome, routed.provider(), outcome.segments());
        long units = channel == Channel.SMS ? Math.max(1, outcome.segments()) : 1L;
        PipelineVerdict quota = pipeline.checkQuota(stream, channel, units, cost.orElse(null), now);
        if (quota.isRejected()) {
            return reject(message, quota.reason(), quota.detail(), now);
        }

        StatusChange change = applyRoute(message, outcome, routed, filter, cost.orElse(null), now);
        pipeline.registerSend(message, channel, stream, units, cost.orElse(null), now);
        messages.save(message);
        notifier.publish(message, change);
        notifier.recordAccepted(message);
        touch(stream, now);
        return mapper.toSubmitResult(message);
    }

    /**
     * Records the decision on the aggregate and queues it unless it must wait.
     *
     * <p>A message deferred by quiet hours or by its send window stays {@code ROUTED}: the dispatcher
     * re-evaluates the window every turn, so nothing has to be rescheduled (FR-5.3, FR-8.5).
     */
    private static StatusChange applyRoute(
            Message message,
            RoutingOutcome outcome,
            RoutingResult.Routed routed,
            FilterVerdict filter,
            Money cost,
            Instant now) {
        StatusChange change = message.markRouted(routed.channel(), routed.provider(), Actor.system(), now);
        if (routed.channel() == Channel.SMS && outcome.segments() > 0) {
            message.applySegments(outcome.segments());
        }
        if (cost != null) {
            message.applyCost(cost);
        }
        if (filter.isDeferred() || !message.timing().isSendableAt(now)) {
            return change;
        }
        return message.markQueued(Actor.system(), now);
    }

    private Message build(
            SubmitMessageCommand command,
            Stream stream,
            TrafficClass trafficClass,
            MessageContents contents,
            DedupKey dedupKey,
            Instant now) {
        MessageEnvelope envelope = envelopeOf(command, stream, trafficClass, dedupKey);
        Message message = Message.accept(
                envelope,
                command.recipient(),
                planOf(command, contents),
                contents,
                command.template(),
                command.delivery().timingOptional().orElseGet(Timing::immediate),
                now);
        if (command.delivery().test()) {
            message.markAsTest();
        }
        return message;
    }

    private static MessageEnvelope envelopeOf(
            SubmitMessageCommand command, Stream stream, TrafficClass trafficClass, DedupKey dedupKey) {
        MessageEnvelope envelope = command.batchId() == null
                ? MessageEnvelope.single(command.streamId(), command.externalMessageId(), trafficClass)
                : MessageEnvelope.batched(
                        command.streamId(), command.externalMessageId(), trafficClass, command.batchId());
        envelope = envelope.withPriority(
                        stream.effectivePriority(command.delivery().priority(), trafficClass))
                .withDedupKey(dedupKey);
        return command.delivery()
                .correlationIdOptional()
                .map(envelope::withCorrelationId)
                .orElse(envelope);
    }

    /** Plan of the submission, or a free choice between the channels the content covers (MP-03). */
    private static ChannelPlan planOf(SubmitMessageCommand command, MessageContents contents) {
        return command.channelPlanOptional()
                .orElseGet(() -> ChannelPlan.moduleChoice(List.copyOf(contents.channels())));
    }

    /** Rejection of a message that exists: it keeps its history and its outbound event (ST-01). */
    private SubmitMessageResult reject(Message message, RejectionReason reason, String detail, Instant now) {
        StatusChange change = message.reject(reason, detail, Actor.system(), now);
        messages.save(message);
        notifier.publish(message, change);
        notifier.recordRejected(message.envelope().streamId(), reason);
        return SubmitMessageResult.rejected(message.id(), reason, detail);
    }

    /** Refusal before a message could be created; answered synchronously to the caller (IR-01). */
    private SubmitMessageResult refuse(SubmitMessageCommand command, RejectionReason reason, String detail) {
        notifier.recordRejected(command.streamId(), reason);
        return SubmitMessageResult.rejected(null, reason, detail);
    }

    private SubmitMessageResult duplicate(SubmitMessageCommand command, MessageId original) {
        notifier.recordDuplicate(command.streamId());
        return SubmitMessageResult.duplicate(original);
    }

    /** Keeps the connection status of the source system alive (FR-1.3). */
    private void touch(Stream stream, Instant now) {
        stream.touch(now);
        streams.save(stream);
    }
}
