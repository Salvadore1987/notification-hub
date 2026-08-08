package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/**
 * Suppression list checked before every send (§10.1 {@code suppression_list}, FR-5.1, EM-02).
 *
 * <p>Addresses are looked up by their SHA-256 hash so that no PII is stored in the table (DB-04).
 *
 * <p>Two families of lookup that read alike and are not the same. {@code findActive…} is the sending
 * path: it matches a row of the channel <em>or</em> a row that covers every channel, and it ignores an
 * entry whose validity has elapsed. {@code find…} is the administration path: it matches one exact
 * channel scope ({@code null} being the "all channels" scope of its own) and returns the row whether it
 * is still in force or not, because that row is what the unique index of §10.1 will collide with.
 */
public interface SuppressionRepository {

    SuppressionEntry save(SuppressionEntry entry);

    /**
     * Stores the entry unless the same target is already listed in the same channel scope (EM-02).
     *
     * <p>For the automatic path — a provider that reported the address as unusable, an email hard
     * bounce — where a second report must not become a second row or an error. Implementations resolve
     * the collision in the database, not by reading first: two bounces of the same address arrive
     * concurrently often enough.
     *
     * @return the entry now in force: the one just written, or the one that was already there
     */
    SuppressionEntry saveIfAbsent(SuppressionEntry entry);

    void delete(SuppressionEntryId entryId);

    Optional<SuppressionEntry> findById(SuppressionEntryId entryId);

    /** Active entry banning this address on this channel, if any (FR-5.1). */
    Optional<SuppressionEntry> findActiveByAddress(AddressHash addressHash, Channel channel, Instant now);

    /** Active entry banning the whole client on this channel, if any (FR-5.1). */
    Optional<SuppressionEntry> findActiveByClient(ClientId clientId, Channel channel, Instant now);

    /** Entry of this exact channel scope, in force or expired; {@code null} channel = all channels. */
    Optional<SuppressionEntry> findByAddress(AddressHash addressHash, Channel channel);

    /** Entry of this exact channel scope, in force or expired; {@code null} channel = all channels. */
    Optional<SuppressionEntry> findByClient(ClientId clientId, Channel channel);

    /**
     * Page of the list for the administration screens (FR-5.1, UI-03).
     *
     * <p>Filters are optional — {@code null} means "any" — and the page is always bounded: the list grows
     * with every opt-out and every bounce and has no natural size.
     */
    List<SuppressionEntry> findAll(Channel channel, SuppressionReason reason, ClientId clientId, int limit, int offset);
}
