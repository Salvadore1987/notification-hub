package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One pass of the data-mart export (FR-6.4).
 *
 * <p>Bounded twice on purpose. {@code pageSize} bounds the transaction and the Kafka batch; {@code
 * maxPages} bounds the pass, so a backlog is worked off over several ticks instead of in one run that
 * holds a connection for minutes. Neither is a limit on how much is exported in the end — the next tick
 * continues from the cursor.
 */
public record ExportDeliveryEventsCommand(int pageSize, int maxPages) {

    public static final int DEFAULT_PAGE_SIZE = 500;

    public static final int DEFAULT_MAX_PAGES = 20;

    public ExportDeliveryEventsCommand {
        Guard.positive(pageSize, "ExportDeliveryEventsCommand.pageSize");
        Guard.positive(maxPages, "ExportDeliveryEventsCommand.maxPages");
    }

    public static ExportDeliveryEventsCommand defaults() {
        return new ExportDeliveryEventsCommand(DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGES);
    }
}
