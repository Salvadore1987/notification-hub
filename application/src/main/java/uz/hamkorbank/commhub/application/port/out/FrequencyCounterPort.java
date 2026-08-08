package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;

/**
 * Per-recipient send counters behind the frequency cap of bulk traffic (FR-5.4).
 *
 * <p>Addresses are counted by their hash, so the counter table carries no PII (DB-04). In the MVP the
 * cap only produces counters and alerts; blocking is enabled by configuration later (FR-5.4).
 *
 * <p>The count is a ceiling, not an exact ledger: implementations may aggregate sends into time buckets
 * rather than keep a row per message, so {@link #countSince} may include a little more history than the
 * caller asked for. A cap that errs towards alerting is the harmless direction, and 17 million push
 * messages a month (§12.2) are not worth one row each.
 */
public interface FrequencyCounterPort {

    /** Number of messages sent to the address on the channel since {@code since}. */
    long countSince(AddressHash addressHash, Channel channel, Instant since);

    /** Registers one more send to the address. */
    void register(AddressHash addressHash, Channel channel, Instant occurredAt);

    /** Drops counters older than the cutoff; called by the retention sweep (DB-03). */
    long purgeBefore(Instant cutoff, int limit);
}
