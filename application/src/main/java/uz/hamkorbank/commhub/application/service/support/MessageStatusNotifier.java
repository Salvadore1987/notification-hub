package uz.hamkorbank.commhub.application.service.support;

import java.time.Duration;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.mapper.MessageMapper;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Turns a status change of a message into an outbound event and a metric (ST-01, §6.4, OBS-01).
 *
 * <p>The event is appended to the outbox inside the transaction of the use case; the relay publishes
 * it afterwards, which is what makes "no message lost, no status lost" hold across an instance crash
 * (AD-03, QA-06).
 */
@Component
public class MessageStatusNotifier {

    private final OutboxPort outbox;
    private final MetricsPort metrics;
    private final MessageMapper mapper;

    public MessageStatusNotifier(OutboxPort outbox, MetricsPort metrics, MessageMapper mapper) {
        this.outbox = Guard.notNull(outbox, "outbox");
        this.metrics = Guard.notNull(metrics, "metrics");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** Publishes a canonical status update to the source system (§6.4). */
    public MessageStatusEvent publish(Message message, StatusChange change) {
        Guard.notNull(message, "message");
        Guard.notNull(change, "change");
        MessageStatusEvent event = mapper.toStatusEvent(message, change);
        outbox.append(OutboxEvent.messageStatus(event));
        metrics.statusChanged(
                change.status(),
                message.selectedChannel().orElse(null),
                message.selectedProvider().orElse(null),
                message.isTest());
        return event;
    }

    /**
     * Publishes the status update and, on top of it, the DLQ event of a failed message (FR-3.3).
     *
     * <p>Two events on purpose: source systems follow the status stream, operations tooling follows
     * {@code comm.outbound.dlq.v1} (§8.1 IK-02).
     */
    public MessageStatusEvent publishDlq(Message message, StatusChange change) {
        MessageStatusEvent event = publish(message, change);
        outbox.append(OutboxEvent.messageDlq(event));
        return event;
    }

    /** Counts an accepted submission by stream, traffic class and channel (OBS-01, FR-6.1). */
    public void recordAccepted(Message message) {
        Guard.notNull(message, "message");
        metrics.messageAccepted(
                message.envelope().streamId(),
                message.envelope().trafficClass(),
                message.selectedChannel().orElse(null),
                message.isTest());
    }

    /** Counts a rejected submission by its canonical reason (OBS-04, IR-01). */
    public void recordRejected(StreamId streamId, RejectionReason reason) {
        metrics.messageRejected(streamId, reason);
    }

    /** Counts a submission suppressed by the idempotency check (FR-1.5). */
    public void recordDuplicate(StreamId streamId) {
        metrics.messageDuplicate(streamId);
    }

    /**
     * Records how long a stage of §5.1 took (OBS-01, TC-01).
     *
     * <p>Here rather than in a component of its own so that the use cases keep one collaborator for
     * "report what happened": the services that measure a stage are already the ones publishing its
     * outcome, and an eighth constructor parameter on the sending saga buys nothing.
     */
    public void recordStage(String stage, TrafficClass trafficClass, Duration duration) {
        metrics.stageLatency(stage, trafficClass, duration);
    }
}
