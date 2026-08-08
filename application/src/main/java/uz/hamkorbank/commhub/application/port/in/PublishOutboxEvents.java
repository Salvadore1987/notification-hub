package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;

/**
 * Publishes what the use cases appended to the outbox to the outbound topics (AD-03, §8.1 IK-02).
 *
 * <p>The second half of the transactional outbox: a use case writes its event in the same transaction
 * as the business change and never talks to the broker itself, so a crash between the two can lose
 * neither. Runs on a schedule; one call publishes at most a bounded batch.
 */
public interface PublishOutboxEvents {

    OutboxRelayResult publish(PublishOutboxEventsCommand command);
}
