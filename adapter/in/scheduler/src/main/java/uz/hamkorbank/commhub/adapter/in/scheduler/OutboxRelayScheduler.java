package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.port.in.PublishOutboxEvents;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;

/**
 * Driving adapter that ticks the outbox relay (AD-03).
 *
 * <p>All it does is call the use case on a fixed delay and keep calling it while the outbox still has
 * full batches to give, so a backlog drains at the speed of the broker rather than of the tick. The
 * publishing itself — what to publish, in what order, what a failure means — belongs to the use case.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: passes must not overlap on one instance. Overlap
 * between instances is fine and expected — the claim is {@code SKIP LOCKED}.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final PublishOutboxEvents relay;
    private final OutboxRelayProperties properties;

    public OutboxRelayScheduler(PublishOutboxEvents relay, OutboxRelayProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${commhub.outbox.relay.poll-interval-ms:500}",
            initialDelayString = "${commhub.outbox.relay.poll-interval-ms:500}")
    public void relayOutbox() {
        PublishOutboxEventsCommand command = new PublishOutboxEventsCommand(properties.batchSize());
        for (int pass = 0; pass < properties.maxPassesPerTick(); pass++) {
            OutboxRelayResult result = runPass(command);
            if (result.isIdle() || !result.more()) {
                return;
            }
        }
    }

    private OutboxRelayResult runPass(PublishOutboxEventsCommand command) {
        try {
            OutboxRelayResult result = relay.publish(command);
            if (result.failed() > 0) {
                LOG.warn(
                        "Outbox relay published {} events and left one unpublished; it is retried on the next pass"
                                + " (AD-03) — see outbox_event.last_error",
                        result.published());
            } else if (result.published() > 0) {
                LOG.debug("Outbox relay published {} events", result.published());
            }
            return result;
        } catch (RuntimeException e) {
            // Пропущенный тик безопаснее остановленного планировщика: события останутся в outbox.
            LOG.error("Outbox relay pass failed; events stay in the outbox and are retried (AD-03)", e);
            return OutboxRelayResult.none();
        }
    }
}
