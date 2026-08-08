package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/**
 * Suppression list checked before every send (§10.1 {@code suppression_list}, FR-5.1, EM-02).
 *
 * <p>Addresses are looked up by their SHA-256 hash so that no PII is stored in the table (DB-04).
 */
public interface SuppressionRepository {

    SuppressionEntry save(SuppressionEntry entry);

    void delete(SuppressionEntryId entryId);

    /** Active entry banning this address on this channel, if any (FR-5.1). */
    Optional<SuppressionEntry> findActiveByAddress(AddressHash addressHash, Channel channel, Instant now);

    /** Active entry banning the whole client on this channel, if any (FR-5.1). */
    Optional<SuppressionEntry> findActiveByClient(ClientId clientId, Channel channel, Instant now);
}
