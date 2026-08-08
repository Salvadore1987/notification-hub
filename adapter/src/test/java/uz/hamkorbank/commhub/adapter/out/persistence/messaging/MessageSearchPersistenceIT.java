package uz.hamkorbank.commhub.adapter.out.persistence.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsDimension;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.application.port.out.MessageDigest;
import uz.hamkorbank.commhub.application.port.out.StatisticsRow;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * The read side of the message screens: search (§11.2 "Сообщения") and reports (§11.2, FR-6.2).
 *
 * <p>What is worth checking here is the SQL, not the mapping: the recipient lives in a {@code jsonb}
 * column behind two expression indexes (V14), the period is the partition key (DB-02), and the report
 * groups by an expression chosen from a closed set. All four are things a unit test cannot see.
 */
class MessageSearchPersistenceIT extends AbstractPersistenceIT {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant FROM = ACCEPTED_AT.minusSeconds(3600);
    private static final Instant TO = ACCEPTED_AT.plusSeconds(3600);
    private static final Currency UZS = Currency.getInstance("UZS");
    private static final String MSISDN = "998901234567";
    private static final String OTHER_MSISDN = "998907654321";
    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));

    private final MessagePersistenceAdapter messages;
    private final MessageSearchPersistenceAdapter search;
    private final StatisticsPersistenceAdapter statistics;

    MessageSearchPersistenceIT(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            MessagePersistenceAdapter messages,
            MessageSearchPersistenceAdapter search,
            StatisticsPersistenceAdapter statistics) {
        super(jdbcClient, transactionTemplate);
        this.messages = messages;
        this.search = search;
        this.statistics = statistics;
    }

    @BeforeEach
    void clearMessages() {
        truncate("delivery_attempt", "message_status_history", "message");
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("§11.2: a search by number finds the message and carries its address in the clear")
    void findsByRecipientNumber() {
        // Arrange
        Message wanted = delivered("adm-0000001", MSISDN);
        delivered("adm-0000002", OTHER_MSISDN);

        // Act
        List<MessageDigest> found = search.search(byRecipient(MSISDN));

        // Assert
        assertThat(found).singleElement().satisfies(digest -> {
            assertThat(digest.messageId()).isEqualTo(wanted.id());
            assertThat(digest.recipient()).isEqualTo(MSISDN);
            assertThat(digest.status()).isEqualTo(MessageStatus.DELIVERED);
            assertThat(digest.channel()).isEqualTo(Channel.SMS);
            assertThat(digest.routing().provider()).isEqualTo(ProviderCode.of("PLAYMOBILE"));
            assertThat(digest.routing().segments()).isEqualTo(2);
            assertThat(digest.routing().cost().amount()).isEqualByComparingTo("241.0000");
        });
        assertThat(search.count(byRecipient(MSISDN))).isEqualTo(1);
    }

    @Test
    @DisplayName("§11.2: the other identifiers of the screen find the same row")
    void findsByTheOtherIdentifiers() {
        // Arrange
        delivered("adm-0000003", MSISDN);

        // Act + Assert
        assertThat(search.search(filtered(new MessageSearchQuery.MessageFilter("adm-0000003", null, null, null))))
                .hasSize(1);
        assertThat(search.search(filtered(new MessageSearchQuery.MessageFilter(null, null, "corr-adm-0000003", null))))
                .hasSize(1);
        assertThat(search.search(filtered(new MessageSearchQuery.MessageFilter("adm-0000009", null, null, null))))
                .isEmpty();
    }

    @Test
    @DisplayName("DB-02: the period bounds the search, so a screen never reads outside its window")
    void periodBoundsTheSearch() {
        // Arrange
        delivered("adm-0000004", MSISDN);

        // Act + Assert
        assertThat(search.search(new MessageSearchQuery(ACCEPTED_AT.plusSeconds(1), TO, null, null, null, null, 50, 0)))
                .isEmpty();
        assertThat(search.count(new MessageSearchQuery(FROM, ACCEPTED_AT, null, null, null, null, 50, 0)))
                .isZero();
    }

    @Test
    @DisplayName("UI-03: paging walks the rows once, most recently accepted first")
    void pagesMostRecentFirst() {
        // Arrange
        delivered("adm-0000005", MSISDN);
        delivered("adm-0000006", MSISDN);
        delivered("adm-0000007", MSISDN);

        // Act
        List<MessageDigest> first = search.search(paged(2, 0));
        List<MessageDigest> second = search.search(paged(2, 2));

        // Assert
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first)
                .extracting(MessageDigest::messageId)
                .doesNotContainAnyElementsOf(
                        second.stream().map(MessageDigest::messageId).toList());
        assertThat(search.count(paged(2, 0))).isEqualTo(3);
    }

    @Test
    @DisplayName("§11.2: a rejected message is still on the list, with its reason and no provider")
    void rejectedMessagesAreListed() {
        // Arrange
        Message message = accepted("adm-0000008", MSISDN);
        message.reject(RejectionReason.SUPPRESSED, "recipient is suppressed", Actor.system(), ACCEPTED_AT);
        messages.save(message);

        // Act
        List<MessageDigest> found = search.search(byRecipient(MSISDN));

        // Assert
        assertThat(found).singleElement().satisfies(digest -> {
            assertThat(digest.status()).isEqualTo(MessageStatus.REJECTED);
            assertThat(digest.routing().reason()).isEqualTo(RejectionReason.SUPPRESSED);
            assertThat(digest.routing().provider()).isNull();
            assertThat(digest.channel()).isEqualTo(Channel.SMS);
        });
    }

    // ---------------------------------------------------------------- statistics

    @Test
    @DisplayName("FR-6.2: the report counts messages per channel and sums their cost")
    void reportsPerChannel() {
        // Arrange
        delivered("adm-0000010", MSISDN);
        delivered("adm-0000011", MSISDN);
        Message rejected = accepted("adm-0000012", MSISDN);
        rejected.reject(RejectionReason.SUPPRESSED, "suppressed", Actor.system(), ACCEPTED_AT);
        messages.save(rejected);

        // Act
        List<StatisticsRow> rows = statistics.aggregate(StatisticsQuery.of(FROM, TO, StatisticsDimension.CHANNEL));

        // Assert
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("SMS");
            assertThat(row.accepted()).isEqualTo(3);
            assertThat(row.delivered()).isEqualTo(2);
            assertThat(row.rejected()).isEqualTo(1);
            assertThat(row.failed()).isZero();
            assertThat(row.segments()).isEqualTo(4);
            assertThat(row.cost().amount()).isEqualByComparingTo("482.0000");
            assertThat(row.deliveryRate()).isEqualTo(2.0 / 3);
        });
    }

    @Test
    @DisplayName("FR-6.2: a per-provider report leaves out what never reached a provider")
    void reportsPerProvider() {
        // Arrange
        delivered("adm-0000013", MSISDN);
        Message rejected = accepted("adm-0000014", MSISDN);
        rejected.reject(RejectionReason.SUPPRESSED, "suppressed", Actor.system(), ACCEPTED_AT);
        messages.save(rejected);

        // Act
        List<StatisticsRow> rows = statistics.aggregate(StatisticsQuery.of(FROM, TO, StatisticsDimension.PROVIDER));

        // Assert
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("PLAYMOBILE");
            assertThat(row.accepted()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("FR-7.4: a test send is out of the report unless it is asked for")
    void testSendsAreADimensionNotAdeletion() {
        // Arrange
        delivered("adm-0000015", MSISDN);
        Message test = accepted("adm-0000016", MSISDN);
        test.markAsTest();
        messages.save(test);

        // Act
        List<StatisticsRow> without = statistics.aggregate(StatisticsQuery.of(FROM, TO, StatisticsDimension.CHANNEL));
        List<StatisticsRow> with = statistics.aggregate(
                new StatisticsQuery(FROM, TO, StatisticsDimension.CHANNEL, null, null, null, null, true));

        // Assert
        assertThat(without).singleElement().satisfies(row -> assertThat(row.accepted())
                .isEqualTo(1));
        assertThat(with).singleElement().satisfies(row -> assertThat(row.accepted())
                .isEqualTo(2));
    }

    @Test
    @DisplayName("TC-01: the OTP p99 is empty when the class saw no traffic, and a figure when it did")
    void reportsOtpLatency() {
        // Arrange
        delivered("adm-0000017", MSISDN);

        // Act
        OptionalLong noOtp = statistics.acceptToProviderP99Millis(FROM, TO, TrafficClass.CRITICAL_OTP);
        OptionalLong transactional = statistics.acceptToProviderP99Millis(FROM, TO, TrafficClass.TRANSACTIONAL);

        // Assert — the fixture is TRANSACTIONAL, so OTP has nothing to report and that is not a zero.
        assertThat(noOtp).isEmpty();
        assertThat(transactional).isPresent();
        assertThat(transactional.getAsLong()).isEqualTo(4_000L);
    }

    // ---------------------------------------------------------------- fixtures

    private static MessageSearchQuery byRecipient(String msisdn) {
        return filtered(new MessageSearchQuery.MessageFilter(null, msisdn, null, null));
    }

    private static MessageSearchQuery filtered(MessageSearchQuery.MessageFilter filter) {
        return new MessageSearchQuery(FROM, TO, filter, null, null, null, 50, 0);
    }

    private static MessageSearchQuery paged(int limit, int offset) {
        return new MessageSearchQuery(FROM, TO, null, null, null, null, limit, offset);
    }

    private Message delivered(String externalId, String msisdn) {
        Message message = messages.save(accepted(externalId, msisdn));
        message.markValidated(Actor.system(), ACCEPTED_AT.plusSeconds(1));
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), ACCEPTED_AT.plusSeconds(2));
        message.applySegments(2);
        message.applyCost(Money.of(new BigDecimal("241.0000"), UZS));
        message.markQueued(Actor.system(), ACCEPTED_AT.plusSeconds(3));
        message.markSending(Actor.system(), ACCEPTED_AT.plusSeconds(4));
        message.startAttempt(ProviderMessageId.of("pm-" + externalId), ACCEPTED_AT.plusSeconds(4))
                .succeed("200", ProviderMessageId.of("pm-" + externalId), ACCEPTED_AT.plusSeconds(5));
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(5));
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), ACCEPTED_AT.plusSeconds(30));
        return messages.save(message);
    }

    /** The correlation id is pinned so the search-by-correlationId test has something to look for. */
    private static MessageEnvelope envelope(String externalId) {
        MessageEnvelope generated = MessageEnvelope.single(
                StreamId.of("mobile-app"), ExternalMessageId.of(externalId), TrafficClass.TRANSACTIONAL);
        return new MessageEnvelope(
                generated.id(),
                generated.externalId(),
                generated.streamId(),
                generated.batchId(),
                generated.trafficClass(),
                generated.priority(),
                generated.dedupKey(),
                CorrelationId.of("corr-" + externalId));
    }

    /**
     * A freshly accepted message, <em>not</em> saved.
     *
     * <p>The caller saves, which matters for the TEST flag: {@code message.test} is written on insert and
     * deliberately left out of the upsert's {@code DO UPDATE}, because whether a send is a test is decided
     * when it is accepted and never afterwards (FR-7.4).
     */
    private static Message accepted(String externalId, String msisdn) {
        return Message.accept(
                envelope(externalId),
                Recipient.ofMsisdn(Msisdn.of(msisdn)),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("Код 1234", "HAMKORBANK")),
                null,
                Timing.immediate(),
                ACCEPTED_AT);
    }
}
