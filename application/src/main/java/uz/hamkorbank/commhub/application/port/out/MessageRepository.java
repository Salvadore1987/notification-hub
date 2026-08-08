package uz.hamkorbank.commhub.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * Persistence of the {@code Message} aggregate together with its status history and delivery
 * attempts (§10.1 {@code message}, {@code message_status_history}, {@code delivery_attempt}).
 *
 * <p>Implementations save the aggregate and everything appended to it since it was loaded, inside the
 * transaction opened by the use case — the same transaction that writes the outbox event (AD-03).
 */
public interface MessageRepository {

    /** Inserts or updates the aggregate with its new status history entries and attempts. */
    Message save(Message message);

    Optional<Message> findById(MessageId messageId);

    /** Lookup by the identifier of the source system (§8.2 {@code GET /messages}). */
    Optional<Message> findByExternalId(StreamId streamId, ExternalMessageId externalMessageId);

    /** Lookup used by provider callbacks, which only know the provider-side id (PM-02, SG-02). */
    Optional<Message> findByProviderMessageId(ProviderCode providerCode, ProviderMessageId providerMessageId);

    /**
     * Messages of a traffic class waiting to be handed to a provider, oldest first.
     *
     * <p>Selects the non-terminal statuses {@code ROUTED}, {@code QUEUED} and {@code RETRYING} whose
     * send window is open at {@code now}; separate calls per traffic class keep the OTP pool isolated
     * from bulk load (TC-01, DB-05).
     */
    List<Message> findDispatchable(TrafficClass trafficClass, Instant now, int limit);

    /** In-flight messages whose TTL or send window has elapsed (FR-3.4). */
    List<Message> findExpired(Instant now, int limit);

    /** Number of messages of a batch that already reached a terminal status (FR-3.1). */
    long countTerminalByBatch(BatchId batchId);
}
