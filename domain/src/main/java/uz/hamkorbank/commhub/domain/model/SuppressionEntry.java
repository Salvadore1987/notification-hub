package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A ban on sending to an address or to a whole client (§6.1, FR-5.1, EM-02).
 *
 * <p>Addresses are stored as a {@link AddressHash} so that the suppression list carries no PII
 * (DB-04). A {@code null} channel suppresses every channel; a {@code null} {@code validUntil} makes
 * the entry permanent.
 */
public final class SuppressionEntry extends AggregateRoot<SuppressionEntryId> {

    public static final int MAX_CREATED_BY_LENGTH = 128;

    private final Channel channel;
    private final AddressHash addressHash;
    private final ClientId clientId;
    private final SuppressionReason reason;
    private final Instant createdAt;
    private final String createdBy;

    private Instant validUntil;

    private SuppressionEntry(
            SuppressionEntryId id,
            Channel channel,
            AddressHash addressHash,
            ClientId clientId,
            SuppressionReason reason,
            Instant createdAt,
            String createdBy) {
        super(id);
        this.channel = channel;
        this.addressHash = addressHash;
        this.clientId = clientId;
        this.reason = Guard.notNull(reason, "SuppressionEntry.reason");
        this.createdAt = Guard.notNull(createdAt, "SuppressionEntry.createdAt");
        this.createdBy = Guard.maxLength(createdBy, MAX_CREATED_BY_LENGTH, "SuppressionEntry.createdBy");
        Guard.isTrue(
                addressHash != null || clientId != null,
                "SuppressionEntry requires an addressHash, a clientId or both");
    }

    /** Suppresses one address on one channel (FR-5.1). */
    public static SuppressionEntry forAddress(
            SuppressionEntryId id,
            Channel channel,
            AddressHash addressHash,
            SuppressionReason reason,
            Instant createdAt,
            String createdBy) {
        Guard.notNull(addressHash, "addressHash");
        return new SuppressionEntry(id, channel, addressHash, null, reason, createdAt, createdBy);
    }

    /** Suppresses a client; {@code channel} {@code null} covers every channel (FR-5.1). */
    public static SuppressionEntry forClient(
            SuppressionEntryId id,
            Channel channel,
            ClientId clientId,
            SuppressionReason reason,
            Instant createdAt,
            String createdBy) {
        Guard.notNull(clientId, "clientId");
        return new SuppressionEntry(id, channel, null, clientId, reason, createdAt, createdBy);
    }

    /** Limits the entry in time, e.g. a temporary opt-out (FR-5.1). */
    public void expireAt(Instant expiry) {
        Guard.notNull(expiry, "expiry");
        Guard.isTrue(expiry.isAfter(createdAt), "expiry must be after createdAt");
        this.validUntil = expiry;
    }

    public void makePermanent() {
        this.validUntil = null;
    }

    public boolean isActiveAt(Instant now) {
        Guard.notNull(now, "now");
        return validUntil == null || now.isBefore(validUntil);
    }

    /** Whether the entry blocks sending to this address on this channel. */
    public boolean matchesAddress(Channel candidateChannel, AddressHash candidateAddress) {
        return addressHash != null && addressHash.equals(candidateAddress) && coversChannel(candidateChannel);
    }

    /** Whether the entry blocks sending to this client on this channel. */
    public boolean matchesClient(Channel candidateChannel, ClientId candidateClient) {
        return clientId != null && clientId.equals(candidateClient) && coversChannel(candidateChannel);
    }

    public boolean coversChannel(Channel candidateChannel) {
        return channel == null || channel == candidateChannel;
    }

    public Optional<Channel> channel() {
        return Optional.ofNullable(channel);
    }

    public Optional<AddressHash> addressHash() {
        return Optional.ofNullable(addressHash);
    }

    public Optional<ClientId> clientId() {
        return Optional.ofNullable(clientId);
    }

    public SuppressionReason reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<String> createdBy() {
        return Optional.ofNullable(createdBy);
    }

    public Optional<Instant> validUntil() {
        return Optional.ofNullable(validUntil);
    }
}
