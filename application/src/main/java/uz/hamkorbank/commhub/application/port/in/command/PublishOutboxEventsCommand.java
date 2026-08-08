package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One pass of the outbox relay (AD-03).
 *
 * @param limit how many events one pass may publish; the batch is claimed for the whole transaction,
 *     so a large value means holding rows — and the broker — for longer than a status update deserves
 */
public record PublishOutboxEventsCommand(int limit) {

    public static final int DEFAULT_LIMIT = 200;

    public PublishOutboxEventsCommand {
        Guard.positive(limit, "PublishOutboxEventsCommand.limit");
    }

    public static PublishOutboxEventsCommand defaults() {
        return new PublishOutboxEventsCommand(DEFAULT_LIMIT);
    }
}
