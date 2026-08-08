package uz.hamkorbank.commhub.application.port.in.query;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the suppression list (FR-5.1, UI-03).
 *
 * <p>Every filter is optional — {@code null} means "any" — but the page size never is: the list grows with
 * every opt-out and every hard bounce, so there is no size at which reading all of it stays safe.
 *
 * <p>There is deliberately no filter by address. The table stores hashes (DB-04), so "show me this number"
 * is a different question with a different answer — {@link SuppressionCheckQuery}.
 */
public record SuppressionQuery(Channel channel, SuppressionReason reason, ClientId clientId, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public SuppressionQuery {
        Guard.isTrue(limit <= MAX_LIMIT, "SuppressionQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "SuppressionQuery.limit");
        Guard.notNegative(offset, "SuppressionQuery.offset");
    }

    /** First page of the whole list. */
    public static SuppressionQuery firstPage() {
        return new SuppressionQuery(null, null, null, DEFAULT_LIMIT, 0);
    }

    /** First page of the entries of one channel. */
    public static SuppressionQuery ofChannel(Channel channel) {
        return new SuppressionQuery(channel, null, null, DEFAULT_LIMIT, 0);
    }
}
