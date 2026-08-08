package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One TTL sweep over the in-flight messages (FR-3.4).
 *
 * @param limit maximum number of messages to expire in this run; keeps the transaction short
 */
public record ExpireMessagesCommand(int limit) {

    public static final int DEFAULT_LIMIT = 500;

    public ExpireMessagesCommand {
        Guard.positive(limit, "ExpireMessagesCommand.limit");
    }

    public static ExpireMessagesCommand defaults() {
        return new ExpireMessagesCommand(DEFAULT_LIMIT);
    }
}
