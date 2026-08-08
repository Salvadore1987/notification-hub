package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.dto.OutboxPayload;
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.dto.PushTokenInvalidatedEvent;
import uz.hamkorbank.commhub.application.port.in.PublishOutboxEvents;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.OutboxPort;
import uz.hamkorbank.commhub.application.port.out.PendingOutboxEvent;
import uz.hamkorbank.commhub.application.port.out.StatusPublisherPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Polling publisher of the transactional outbox (AD-03, §8.1 IK-02).
 *
 * <p>One pass runs in a single transaction: claim a batch, publish it, mark what the broker
 * acknowledged. The order matters and is the whole point — an event is marked published only after the
 * acknowledgement, so a crash anywhere in between leaves it unpublished and it goes out again on the
 * next pass. That is at-least-once: nothing is lost, a duplicate is possible, and consumers deduplicate
 * by {@code eventId} (FR-1.5, QA-06).
 *
 * <p><strong>A failed event stops the pass.</strong> Statuses of one message are ordered on the topic
 * by their partition key, and skipping a failure to publish the next event of the same message would
 * deliver {@code DELIVERED} before {@code SENT_TO_PROVIDER}. The usual cause is a broker that is down,
 * where continuing is pointless anyway; the rare one is a single event the broker rejects, which then
 * holds up its queue — visible as {@code attempts} climbing on the row, deliberately loud rather than
 * silently dropped.
 */
@Service
public class PublishOutboxEventsService implements PublishOutboxEvents {

    private final OutboxPort outbox;
    private final StatusPublisherPort publisher;
    private final ClockPort clock;

    public PublishOutboxEventsService(OutboxPort outbox, StatusPublisherPort publisher, ClockPort clock) {
        this.outbox = Guard.notNull(outbox, "outbox");
        this.publisher = Guard.notNull(publisher, "publisher");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    @Transactional
    public OutboxRelayResult publish(PublishOutboxEventsCommand command) {
        Guard.notNull(command, "command");
        List<PendingOutboxEvent> pending = outbox.pollUnpublished(command.limit());
        int published = 0;
        for (PendingOutboxEvent event : pending) {
            try {
                publish(event);
            } catch (RuntimeException e) {
                outbox.markFailed(event, describe(e));
                return new OutboxRelayResult(published, 1, false);
            }
            outbox.markPublished(event, clock.now());
            published++;
        }
        return new OutboxRelayResult(published, 0, pending.size() >= command.limit());
    }

    private void publish(PendingOutboxEvent event) {
        switch (event.type()) {
            case MESSAGE_STATUS -> publisher.publishStatus(payload(event, MessageStatusEvent.class));
            case MESSAGE_DLQ -> publisher.publishDlq(payload(event, MessageStatusEvent.class));
            case PUSH_TOKEN_INVALIDATED ->
                publisher.publishPushTokenInvalidated(payload(event, PushTokenInvalidatedEvent.class));
            // Новый тип события без своего топика лучше остановит очередь, чем уйдёт не туда.
            default -> throw new IllegalStateException("No outbound topic for outbox event type " + event.type());
        }
    }

    /**
     * Payload of the row in the shape its type promises.
     *
     * <p>A mismatch is a bug in the store, not bad traffic, and it stops the queue on purpose: a status
     * row holding a token payload would otherwise be published onto the status topic as something no
     * consumer can parse.
     */
    private static <T extends OutboxPayload> T payload(PendingOutboxEvent event, Class<T> type) {
        if (!type.isInstance(event.payload())) {
            throw new IllegalStateException("Outbox event %s of type %s carries a %s payload"
                    .formatted(
                            event.eventId(),
                            event.type(),
                            event.payload().getClass().getSimpleName()));
        }
        return type.cast(event.payload());
    }

    /** Keeps the class of the failure, which is what identifies it, even when the message is empty. */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
