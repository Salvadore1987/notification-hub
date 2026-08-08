package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Storage behind the compliance filters: suppression administration and frequency counters (FR-5.1, FR-5.4). */
class CompliancePersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-08-08T09:30:00Z");
    private static final AddressHash HASH = AddressHash.ofMsisdn(Msisdn.of("998901234567"));

    private final SuppressionPersistenceAdapter suppression;
    private final FrequencyCounterPersistenceAdapter counters;

    CompliancePersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            SuppressionPersistenceAdapter suppression,
            FrequencyCounterPersistenceAdapter counters) {
        super(jdbcClient, transactionTemplate);
        this.suppression = suppression;
        this.counters = counters;
    }

    @BeforeEach
    void clearTables() {
        truncate("suppression_list", "frequency_counter");
    }

    @Test
    @DisplayName("a second report of the same address leaves the entry that is there (EM-02)")
    void saveIfAbsentKeepsTheFirstEntry() {
        // Arrange
        SuppressionEntry first = addressEntry(SuppressionReason.HARD_BOUNCE);
        suppression.saveIfAbsent(first);

        // Act
        SuppressionEntry second = suppression.saveIfAbsent(addressEntry(SuppressionReason.PROVIDER_BLACKLIST));

        // Assert
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.reason()).isEqualTo(SuppressionReason.HARD_BOUNCE);
        assertThat(rowsIn("suppression_list")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an expired entry is still found by the administration lookup, which the index collides with")
    void findsExpiredEntryForAdministration() {
        // Arrange
        SuppressionEntry entry = addressEntry(SuppressionReason.DELIVERY_FAILURES);
        entry.expireAt(NOW.plus(Duration.ofHours(1)));
        suppression.save(entry);

        // Act
        Optional<SuppressionEntry> forAdmin = suppression.findByAddress(HASH, Channel.SMS);
        Optional<SuppressionEntry> forSending =
                suppression.findActiveByAddress(HASH, Channel.SMS, NOW.plus(Duration.ofHours(2)));

        // Assert
        assertThat(forAdmin).isPresent();
        assertThat(forAdmin.orElseThrow().validUntil()).contains(NOW.plus(Duration.ofHours(1)));
        assertThat(forSending).isEmpty();
    }

    @Test
    @DisplayName("the all-channels scope is a scope of its own, not a match for every channel")
    void distinguishesTheAllChannelScope() {
        // Arrange
        suppression.save(SuppressionEntry.forAddress(
                SuppressionEntryId.newId(), null, HASH, SuppressionReason.OPT_OUT, NOW, "operator-1"));

        // Act
        Optional<SuppressionEntry> allChannels = suppression.findByAddress(HASH, null);
        Optional<SuppressionEntry> smsScope = suppression.findByAddress(HASH, Channel.SMS);

        // Assert
        assertThat(allChannels).isPresent();
        assertThat(smsScope).isEmpty();
    }

    @Test
    @DisplayName("the listing filters by channel and reason and comes back newest first (UI-03)")
    void listsFilteredPage() {
        // Arrange
        suppression.save(addressEntry(SuppressionReason.HARD_BOUNCE));
        suppression.save(SuppressionEntry.forClient(
                SuppressionEntryId.newId(),
                Channel.SMS,
                ClientId.of("CL-7"),
                SuppressionReason.OPT_OUT,
                NOW.plusSeconds(60),
                "operator-1"));
        suppression.save(SuppressionEntry.forClient(
                SuppressionEntryId.newId(),
                Channel.EMAIL,
                ClientId.of("CL-8"),
                SuppressionReason.OPT_OUT,
                NOW.plusSeconds(120),
                "operator-1"));

        // Act
        List<SuppressionEntry> smsPage = suppression.findAll(Channel.SMS, null, null, 50, 0);
        List<SuppressionEntry> optOuts = suppression.findAll(null, SuppressionReason.OPT_OUT, null, 50, 0);
        List<SuppressionEntry> ofClient = suppression.findAll(null, null, ClientId.of("CL-8"), 50, 0);

        // Assert
        assertThat(smsPage).hasSize(2);
        assertThat(smsPage.getFirst().clientId()).contains(ClientId.of("CL-7"));
        assertThat(optOuts).hasSize(2);
        assertThat(ofClient).hasSize(1);
    }

    @Test
    @DisplayName("frequency counters accumulate per hour bucket and are summed over the window (FR-5.4)")
    void countsSendsInsideTheWindow() {
        // Arrange
        counters.register(HASH, Channel.SMS, NOW);
        counters.register(HASH, Channel.SMS, NOW.plusSeconds(30));
        counters.register(HASH, Channel.SMS, NOW.plus(Duration.ofHours(2)));
        counters.register(HASH, Channel.EMAIL, NOW);

        // Act
        long lastDay = counters.countSince(HASH, Channel.SMS, NOW.minus(Duration.ofHours(24)));
        long lastHour = counters.countSince(HASH, Channel.SMS, NOW.plus(Duration.ofHours(2)));
        long otherChannel = counters.countSince(HASH, Channel.EMAIL, NOW.minus(Duration.ofHours(24)));

        // Assert
        assertThat(lastDay).isEqualTo(3L);
        assertThat(lastHour).isEqualTo(1L);
        assertThat(otherChannel).isEqualTo(1L);
        assertThat(rowsIn("frequency_counter")).isEqualTo(3L);
    }

    @Test
    @DisplayName("the sweep drops buckets older than the retention and keeps the window (DB-03)")
    void purgesOldBuckets() {
        // Arrange
        counters.register(HASH, Channel.SMS, NOW.minus(Duration.ofDays(10)));
        counters.register(HASH, Channel.SMS, NOW);

        // Act
        long purged = counters.purgeBefore(NOW.minus(Duration.ofDays(7)), 100);

        // Assert
        assertThat(purged).isEqualTo(1L);
        assertThat(counters.countSince(HASH, Channel.SMS, NOW.minus(Duration.ofDays(30))))
                .isEqualTo(1L);
    }

    private static SuppressionEntry addressEntry(SuppressionReason reason) {
        return SuppressionEntry.forAddress(SuppressionEntryId.newId(), Channel.SMS, HASH, reason, NOW, "operator-1");
    }

    private long rowsIn(String table) {
        return jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
