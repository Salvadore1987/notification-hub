package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import uz.hamkorbank.commhub.application.dto.EventExportResult;
import uz.hamkorbank.commhub.application.port.in.ExportDeliveryEvents;
import uz.hamkorbank.commhub.application.port.in.command.ExportDeliveryEventsCommand;
import uz.hamkorbank.commhub.application.port.out.AnalyticsPublisherPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DeliveryEvent;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository;
import uz.hamkorbank.commhub.application.port.out.EventExportRepository.ExportCursor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Walks finished sends into the Bank's data mart, page by page (FR-6.4).
 *
 * <p>Shaped like the outbox relay and for the same reasons: read a page, publish it, record how far it
 * got — in that order, and never the other way round. The cursor is written only after the broker has
 * acknowledged the page, so a crash mid-pass repeats a page instead of losing one; the feed is
 * at-least-once and the mart deduplicates by message id (AD-03).
 *
 * <p>A page that fails to publish ends the pass rather than skipping ahead. The mart is an ordered feed,
 * and skipping would leave a hole nobody notices until a monthly report is short.
 *
 * <p>The pass itself is not one transaction, unlike the outbox relay next door. The relay must hold its
 * claimed rows until they are marked; this reads rows nobody else touches, so each page borrows a
 * transaction from the repository and gives it back. An export catching up over a week of traffic would
 * otherwise hold one connection and one snapshot for the whole backfill.
 */
@Service
public class ExportDeliveryEventsService implements ExportDeliveryEvents {

    /** Name of the feed in {@code export_cursor}; a second consumer would get a name of its own. */
    public static final String FEED = "data-mart";

    private final EventExportRepository repository;
    private final AnalyticsPublisherPort publisher;
    private final ClockPort clock;

    public ExportDeliveryEventsService(
            EventExportRepository repository, AnalyticsPublisherPort publisher, ClockPort clock) {
        this.repository = Guard.notNull(repository, "repository");
        this.publisher = Guard.notNull(publisher, "publisher");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    public EventExportResult export(ExportDeliveryEventsCommand command) {
        Guard.notNull(command, "command");
        ExportCursor cursor = cursor();
        int exported = 0;
        for (int page = 0; page < command.maxPages(); page++) {
            List<DeliveryEvent> events =
                    repository.findTerminalAfter(cursor.position(), cursor.lastMessageId(), command.pageSize());
            if (events.isEmpty()) {
                return new EventExportResult(exported, cursor.position(), true);
            }
            publisher.publish(events);
            cursor = cursor.advancedTo(events.get(events.size() - 1), clock.now());
            repository.saveCursor(cursor);
            exported += events.size();
            if (events.size() < command.pageSize()) {
                return new EventExportResult(exported, cursor.position(), true);
            }
        }
        return new EventExportResult(exported, cursor.position(), false);
    }

    /**
     * The stored cursor, or a new one starting at the current moment.
     *
     * <p>A first run deliberately exports nothing retroactively: the mart is loaded historically by the
     * data team from the database, and an export that woke up and replayed every message ever sent would
     * be a surprise measured in millions of records.
     */
    private ExportCursor cursor() {
        Instant now = clock.now();
        return repository.findCursor(FEED).orElseGet(() -> {
            ExportCursor initial = ExportCursor.initial(FEED, now, now);
            repository.saveCursor(initial);
            return initial;
        });
    }
}
