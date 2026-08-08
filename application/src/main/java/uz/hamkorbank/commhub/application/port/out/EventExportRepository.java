package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads finished sends for the data-mart feed and remembers how far it got (FR-6.4).
 *
 * <p>The cursor is stored rather than derived, and it is stored in the same database as the messages it
 * points into: the export then advances in the same transaction that reads, so a crash between reading
 * and publishing replays a page instead of skipping it. The feed is at-least-once, like everything else
 * the Hub emits (AD-03), and the mart deduplicates by message id.
 */
public interface EventExportRepository {

    /** Where the named export got to; empty before its first run. */
    Optional<ExportCursor> findCursor(String name);

    void saveCursor(ExportCursor cursor);

    /**
     * The next page of messages that reached a terminal status after the cursor, oldest first.
     *
     * <p>Ordered by {@code (terminalAt, messageId)} and read strictly after that pair, because several
     * messages finish within the same microsecond and a cursor on the timestamp alone would either skip
     * them or repeat them forever.
     */
    List<DeliveryEvent> findTerminalAfter(Instant after, MessageId lastMessageId, int limit);

    /**
     * Position of an export: the last message it published, by the pair it is ordered on.
     *
     * @param name identifies the feed, so a second consumer can be added later without disturbing this one
     */
    record ExportCursor(String name, Instant position, MessageId lastMessageId, Instant updatedAt) {

        public ExportCursor {
            Guard.notBlank(name, "ExportCursor.name");
            Guard.notNull(position, "ExportCursor.position");
            Guard.notNull(updatedAt, "ExportCursor.updatedAt");
        }

        /** Cursor of an export that has not run yet: everything terminal since {@code from}. */
        public static ExportCursor initial(String name, Instant from, Instant now) {
            return new ExportCursor(name, from, null, now);
        }

        public ExportCursor advancedTo(DeliveryEvent event, Instant now) {
            return new ExportCursor(name, event.outcome().terminalAt(), event.messageId(), now);
        }
    }
}
