package uz.hamkorbank.commhub.application.port.in.query;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the message list of the admin panel (§11.2 "Сообщения", UI-03).
 *
 * <p>Every filter is optional — {@code null} means "any" — except the period and the page size. The
 * period is not optional and is not a default the caller may drop: {@code message} is partitioned by
 * {@code accepted_at} (DB-02), and a query without it is a scan of every partition the retention rules
 * kept, which is exactly the query that takes the admin panel down at the moment somebody needs it.
 *
 * <p>The recipient filter takes the address as the operator has it — a number, a mailbox — and never a
 * hash: {@code message.recipient} is stored in clear precisely so this question can be asked (DB-05).
 * What the answer shows is masked on the way out, per role, in the adapter.
 *
 * @param requestedBy who is asking; an operator reading customer messages is journalled (SEC-08)
 */
public record MessageSearchQuery(
        Instant from,
        Instant to,
        MessageFilter filter,
        MessageStatus status,
        StreamId streamId,
        Actor requestedBy,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public MessageSearchQuery {
        Guard.notNull(from, "MessageSearchQuery.from");
        Guard.notNull(to, "MessageSearchQuery.to");
        Guard.isTrue(!to.isBefore(from), "MessageSearchQuery.to precedes MessageSearchQuery.from");
        Guard.isTrue(limit <= MAX_LIMIT, "MessageSearchQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "MessageSearchQuery.limit");
        Guard.notNegative(offset, "MessageSearchQuery.offset");
        filter = filter == null ? MessageFilter.none() : filter;
        requestedBy = requestedBy == null ? Actor.system() : requestedBy;
    }

    /** Everything accepted in the period, most recent first. */
    public static MessageSearchQuery ofPeriod(Instant from, Instant to) {
        return new MessageSearchQuery(from, to, null, null, null, null, DEFAULT_LIMIT, 0);
    }

    /** The same query one page further on; used by the CSV export walk. */
    public MessageSearchQuery nextPage() {
        return new MessageSearchQuery(from, to, filter, status, streamId, requestedBy, limit, offset + limit);
    }

    /**
     * The identifiers the message list is searched by (§11.2).
     *
     * <p>A nested record rather than four more components, because the eight-component ceiling is not the
     * only reason to group them: these four are one question — "which message" — asked in whichever terms
     * the person in front of the screen happens to have.
     *
     * @param recipient address in clear; matched exactly, never by prefix
     * @param batchId drill-down from the batch card to its messages (§11.2 "Рассылки")
     */
    public record MessageFilter(String externalMessageId, String recipient, String correlationId, String batchId) {

        private static final MessageFilter NONE = new MessageFilter(null, null, null, null);

        public static MessageFilter none() {
            return NONE;
        }

        public boolean isEmpty() {
            return externalMessageId == null && recipient == null && correlationId == null && batchId == null;
        }
    }
}
