package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.application.port.out.OutboxEvent;
import uz.hamkorbank.commhub.application.port.out.QuotaScope;
import uz.hamkorbank.commhub.application.port.out.QuotaWindow;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Outbox, dedup registry, suppression list, DLQ and quota counters (AD-03, FR-1.5, FR-5.1, FR-2.6). */
class DeliveryGuaranteesPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final Currency UZS = Currency.getInstance("UZS");

    private final OutboxPersistenceAdapter outbox;
    private final DedupRegistryPersistenceAdapter dedup;
    private final SuppressionPersistenceAdapter suppression;
    private final DlqPersistenceAdapter dlq;
    private final QuotaCounterPersistenceAdapter quotas;

    DeliveryGuaranteesPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            OutboxPersistenceAdapter outbox,
            DedupRegistryPersistenceAdapter dedup,
            SuppressionPersistenceAdapter suppression,
            DlqPersistenceAdapter dlq,
            QuotaCounterPersistenceAdapter quotas) {
        super(jdbcClient, transactionTemplate);
        this.outbox = outbox;
        this.dedup = dedup;
        this.suppression = suppression;
        this.dlq = dlq;
        this.quotas = quotas;
    }

    @BeforeEach
    void clearTables() {
        truncate("outbox_event", "dedup_registry", "suppression_list", "dlq_entry", "quota_counter");
    }

    @Test
    @DisplayName("an outbox event is written inside the caller's transaction and only once (AD-03)")
    void outboxAppendIsIdempotent() {
        // Arrange
        OutboxEvent event = OutboxEvent.messageStatus(statusEvent());

        // Act
        transactions().executeWithoutResult(status -> outbox.append(event));
        transactions().executeWithoutResult(status -> outbox.append(event));

        // Assert
        assertThat(countOutboxRows()).isEqualTo(1L);
        // The row is identified by the outbox event id; the payload keeps the id of the status event
        // it carries, which is what the consumer deduplicates by (§6.4, AD-03).
        assertThat(jdbc().sql("SELECT payload ->> 'eventId' FROM outbox_event WHERE id = :id")
                        .param("id", event.eventId())
                        .query(String.class)
                        .single())
                .isEqualTo(statusEvent().eventId().toString());
    }

    @Test
    @DisplayName("appending without a transaction fails instead of losing the outbox guarantee (AD-03)")
    void outboxRequiresTransaction() {
        // Arrange
        OutboxEvent event = OutboxEvent.messageDlq(statusEvent());

        // Act + Assert
        assertThatThrownBy(() -> outbox.append(event)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countOutboxRows()).isZero();
    }

    @Test
    @DisplayName("a repeated submission inside the window gets the original message back (FR-1.5)")
    void dedupReturnsTheOriginal() {
        // Arrange
        DedupKey key = DedupKey.of("mobile-app:abc0000001");
        MessageId original = MessageId.newId();
        assertThat(dedup.register(key, original, NOW, NOW.plus(Duration.ofHours(24))))
                .isEmpty();

        // Act
        Optional<MessageId> duplicate =
                dedup.register(key, MessageId.newId(), NOW.plusSeconds(5), NOW.plus(Duration.ofHours(24)));

        // Assert
        assertThat(duplicate).contains(original);
        assertThat(dedup.findOriginal(key, NOW.minusSeconds(1))).contains(original);
    }

    @Test
    @DisplayName("after the window has elapsed the key is free again (FR-1.5)")
    void dedupWindowExpires() {
        // Arrange
        DedupKey key = DedupKey.of("mobile-app:abc0000002");
        dedup.register(key, MessageId.newId(), NOW, NOW.plus(Duration.ofHours(1)));
        Instant later = NOW.plus(Duration.ofHours(2));

        // Act
        MessageId resubmission = MessageId.newId();
        Optional<MessageId> verdict = dedup.register(key, resubmission, later, later.plus(Duration.ofHours(24)));

        // Assert
        assertThat(verdict).isEmpty();
        assertThat(dedup.findOriginal(key, later.minusSeconds(1))).contains(resubmission);
    }

    @Test
    @DisplayName("expired dedup entries are purged in bounded batches (DB-03)")
    void purgesExpiredDedupEntries() {
        // Arrange
        dedup.register(DedupKey.of("k1"), MessageId.newId(), NOW, NOW.plus(Duration.ofMinutes(5)));
        dedup.register(DedupKey.of("k2"), MessageId.newId(), NOW, NOW.plus(Duration.ofHours(30)));

        // Act
        long purged = dedup.purgeExpired(NOW.plus(Duration.ofHours(1)), 100);

        // Assert
        assertThat(purged).isEqualTo(1L);
        assertThat(dedup.findOriginal(DedupKey.of("k2"), NOW)).isPresent();
    }

    @Test
    @DisplayName("a channel-wide suppression covers a single channel lookup too (FR-5.1)")
    void suppressionCoversAllChannels() {
        // Arrange
        AddressHash hash = AddressHash.ofMsisdn(Msisdn.of("998901234567"));
        suppression.save(SuppressionEntry.forAddress(
                SuppressionEntryId.newId(), null, hash, SuppressionReason.OPT_OUT, NOW, "operator-1"));

        // Act
        Optional<SuppressionEntry> found = suppression.findActiveByAddress(hash, Channel.SMS, NOW.plusSeconds(60));

        // Assert
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().reason()).isEqualTo(SuppressionReason.OPT_OUT);
    }

    @Test
    @DisplayName("an expired suppression entry no longer blocks delivery (FR-5.1)")
    void expiredSuppressionIsInactive() {
        // Arrange
        SuppressionEntry entry = SuppressionEntry.forClient(
                SuppressionEntryId.newId(),
                Channel.SMS,
                ClientId.of("CL-7"),
                SuppressionReason.COMPLAINT,
                NOW,
                "operator-1");
        entry.expireAt(NOW.plus(Duration.ofHours(1)));
        suppression.save(entry);

        // Act
        Optional<SuppressionEntry> active =
                suppression.findActiveByClient(ClientId.of("CL-7"), Channel.SMS, NOW.plus(Duration.ofHours(2)));
        Optional<SuppressionEntry> stillActive =
                suppression.findActiveByClient(ClientId.of("CL-7"), Channel.SMS, NOW.plusSeconds(30));

        // Assert
        assertThat(active).isEmpty();
        assertThat(stillActive).isPresent();
    }

    @Test
    @DisplayName("a DLQ entry keeps its retry stamp and leaves the retryable list afterwards (FR-3.3)")
    void dlqEntryTracksRetry() {
        // Arrange
        MessageId messageId = MessageId.newId();
        DlqEntry entry = DlqEntry.of(messageId, RejectionReason.ATTEMPTS_EXHAUSTED, "provider 500", NOW);
        dlq.save(entry);
        assertThat(dlq.findRetryable(10)).hasSize(1);

        // Act
        entry.retry("operator-1", NOW.plus(Duration.ofMinutes(5)));
        dlq.save(entry);

        // Assert
        DlqEntry stored = dlq.findByMessageId(messageId).orElseThrow();
        assertThat(stored.retriedBy()).contains("operator-1");
        assertThat(stored.retriedAt()).contains(NOW.plus(Duration.ofMinutes(5)));
        assertThat(dlq.findRetryable(10)).isEmpty();
    }

    @Test
    @DisplayName("a message that fails again lands in the DLQ with the time it landed, not the first one (FR-3.3)")
    void dlqEntryOfARepeatedArrivalCarriesItsOwnTime() {
        // Arrange: the entry has been retried once, and the retried message failed again.
        MessageId messageId = MessageId.newId();
        Instant firstArrival = NOW;
        Instant secondArrival = NOW.plus(Duration.ofHours(3));
        DlqEntry first = DlqEntry.of(messageId, RejectionReason.ATTEMPTS_EXHAUSTED, "provider 500", firstArrival);
        dlq.save(first);
        first.retry("operator-1", NOW.plus(Duration.ofMinutes(5)));
        dlq.save(first);

        // Act: the settlement writes the arrival it has just seen (DispatchSettlement.fail).
        dlq.save(DlqEntry.of(messageId, RejectionReason.ATTEMPTS_EXHAUSTED, "provider timeout", secondArrival));

        // Assert: the row describes the second arrival — including when it happened.
        DlqEntry stored = dlq.findByMessageId(messageId).orElseThrow();
        assertThat(stored.movedAt()).isEqualTo(secondArrival);
        assertThat(stored.lastError()).contains("provider timeout");
        assertThat(stored.retriedAt()).isEmpty();
        assertThat(dlq.findRetryable(10)).hasSize(1);
    }

    @Test
    @DisplayName("quota counters accumulate per day and per month in Asia/Tashkent (FR-2.6)")
    void quotaCountersAccumulate() {
        // Arrange
        QuotaScope scope = QuotaScope.ofStreamChannel(StreamId.of("mobile-app"), Channel.SMS);

        // Act
        quotas.register(scope, 2, Money.of(new BigDecimal("241.0000"), UZS), NOW);
        quotas.register(scope, 3, Money.of(new BigDecimal("120.5000"), UZS), NOW.plus(Duration.ofHours(2)));

        // Assert
        QuotaConfig.Usage daily = quotas.usage(scope, QuotaWindow.DAY, NOW);
        QuotaConfig.Usage monthly = quotas.usage(scope, QuotaWindow.MONTH, NOW);
        assertThat(daily.count()).isEqualTo(5L);
        assertThat(daily.cost().amount()).isEqualByComparingTo("361.5000");
        assertThat(monthly.count()).isEqualTo(5L);
    }

    @Test
    @DisplayName("counters of different scopes do not mix (FR-2.6)")
    void quotaScopesAreIndependent() {
        // Arrange
        QuotaScope stream = QuotaScope.ofStream(StreamId.of("mobile-app"));
        QuotaScope other = QuotaScope.ofStream(StreamId.of("core-banking"));

        // Act
        quotas.register(stream, 7, null, NOW);

        // Assert
        assertThat(quotas.usage(stream, QuotaWindow.DAY, NOW).count()).isEqualTo(7L);
        assertThat(quotas.usage(other, QuotaWindow.DAY, NOW).count()).isZero();
    }

    private long countOutboxRows() {
        return jdbc().sql("SELECT count(*) FROM outbox_event").query(Long.class).single();
    }

    private MessageStatusEvent statusEvent() {
        return new MessageStatusEvent(
                UUID.fromString("0198f0d0-0000-7000-8000-00000000000a"),
                NOW,
                new MessageKey(
                        StreamId.of("mobile-app"),
                        null,
                        MessageId.newId(),
                        ExternalMessageId.of("abc0000001"),
                        CorrelationId.of("corr-1")),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                MessageStatus.DELIVERED,
                "DLVRD",
                null,
                2);
    }
}
