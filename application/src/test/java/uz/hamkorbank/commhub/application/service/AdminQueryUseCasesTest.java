package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.ArchiveDlqResult;
import uz.hamkorbank.commhub.application.dto.DashboardView;
import uz.hamkorbank.commhub.application.dto.DlqEntryView;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.mapper.BatchMapperImpl;
import uz.hamkorbank.commhub.application.mapper.DlqMapperImpl;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.mapper.StatisticsMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.ArchiveDlqCommand;
import uz.hamkorbank.commhub.application.port.in.query.DashboardQuery;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsDimension;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.application.port.out.MessageDigest;
import uz.hamkorbank.commhub.application.port.out.MessageSearchPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StatisticsPort;
import uz.hamkorbank.commhub.application.port.out.StatisticsRow;
import uz.hamkorbank.commhub.application.service.support.PersonalDataAccess;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/** The read side of the admin panel: message search, DLQ, statistics and the dashboard (§11.2). */
class AdminQueryUseCasesTest {

    private static final String MSISDN = "998901234567";

    private static final Instant FROM = NOW.minus(Duration.ofDays(1));

    private ClockPort clock;
    private AuditPort audit;
    private MessageSearchPort messageSearch;
    private DlqRepository dlqEntries;
    private StatisticsPort statistics;
    private BatchRepository batches;
    private ProviderConfigRepository providers;
    private KillSwitchPort killSwitch;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        audit = mock(AuditPort.class);
        messageSearch = mock(MessageSearchPort.class);
        dlqEntries = mock(DlqRepository.class);
        statistics = mock(StatisticsPort.class);
        batches = mock(BatchRepository.class);
        providers = mock(ProviderConfigRepository.class);
        killSwitch = mock(KillSwitchPort.class);
        when(clock.now()).thenReturn(NOW);
    }

    // ---------------------------------------------------------------- message search

    @Test
    @DisplayName("SEC-08: an operator's search is journalled under the hash of the address, not the address")
    void searchIsJournalledWithoutTheAddress() {
        // Arrange
        MessageSearchService service = messageSearchService();
        when(messageSearch.search(any())).thenReturn(List.of(digest()));
        MessageSearchQuery query = new MessageSearchQuery(
                FROM,
                NOW,
                new MessageSearchQuery.MessageFilter(null, MSISDN, null, null),
                null,
                STREAM_ID,
                Actor.operator("a.karimov"),
                50,
                0);

        // Act
        List<MessageDigestView> page = service.search(query);

        // Assert
        assertThat(page).hasSize(1);
        assertThat(page.getFirst().recipient()).isEqualTo(MSISDN);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(PersonalDataAccess.ACTION_SEARCH);
        assertThat(entry.getValue().entityId()).isEqualTo(AddressHash.of(MSISDN).value());
        assertThat(entry.getValue().after()).contains("stream=" + STREAM_ID).doesNotContain(MSISDN);
    }

    @Test
    @DisplayName("SEC-08: a source system's search is not journalled, and neither is a count")
    void systemSearchAndCountAreNotJournalled() {
        // Arrange
        MessageSearchService service = messageSearchService();
        when(messageSearch.search(any())).thenReturn(List.of());
        when(messageSearch.count(any())).thenReturn(7L);
        MessageSearchQuery query = MessageSearchQuery.ofPeriod(FROM, NOW);

        // Act
        service.search(query);
        long total = service.count(new MessageSearchQuery(
                FROM,
                NOW,
                new MessageSearchQuery.MessageFilter(null, MSISDN, null, null),
                null,
                null,
                Actor.operator("a.karimov"),
                50,
                0));

        // Assert
        assertThat(total).isEqualTo(7L);
        verify(audit, never()).write(any());
    }

    // ---------------------------------------------------------------- DLQ

    @Test
    @DisplayName("FR-3.3: archiving reports what it could not take and journals the reason")
    void archivingReportsSkippedEntries() {
        // Arrange
        ArchiveDlqService service = new ArchiveDlqService(clock, dlqEntries, audit);
        MessageId pending = MessageId.newId();
        MessageId alreadyArchived = MessageId.newId();
        MessageId gone = MessageId.newId();
        DlqEntry archived = dlqEntry(alreadyArchived);
        archived.archive();
        when(dlqEntries.findByMessageId(pending)).thenReturn(Optional.of(dlqEntry(pending)));
        when(dlqEntries.findByMessageId(alreadyArchived)).thenReturn(Optional.of(archived));
        when(dlqEntries.findByMessageId(gone)).thenReturn(Optional.empty());

        // Act
        ArchiveDlqResult result = service.archive(new ArchiveDlqCommand(
                List.of(pending, alreadyArchived, gone), Actor.operator("a.karimov"), "campaign cancelled"));

        // Assert
        assertThat(result.archived()).containsExactly(pending);
        assertThat(result.skipped()).containsExactly(alreadyArchived, gone);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().after()).isEqualTo("campaign cancelled");
    }

    @Test
    @DisplayName("§11.2: the DLQ screen reads whether a row can still be acted on from the aggregate")
    void dlqViewCarriesRetryability() {
        // Arrange
        DlqQueryService service = new DlqQueryService(dlqEntries, new DlqMapperImpl());
        DlqEntry retried = dlqEntry(MessageId.newId());
        retried.retry("a.karimov", NOW);
        when(dlqEntries.search(any())).thenReturn(List.of(dlqEntry(MessageId.newId()), retried));

        // Act
        List<DlqEntryView> page = service.list(DlqQuery.pending());

        // Assert
        assertThat(page).extracting(DlqEntryView::retryable).containsExactly(true, false);
        assertThat(page.getLast().retriedBy()).isEqualTo("a.karimov");
    }

    // ---------------------------------------------------------------- statistics and dashboard

    @Test
    @DisplayName("FR-6.2: a report row carries its delivery rate and what is still in flight")
    void reportRowsCarryRateAndInFlight() {
        // Arrange
        StatisticsService service = new StatisticsService(statistics, new StatisticsMapperImpl());
        when(statistics.aggregate(any())).thenReturn(List.of(new StatisticsRow("SMS", 100, 80, 10, 5, 120, uzs(240))));

        // Act
        List<StatisticsRowView> rows = service.report(StatisticsQuery.of(FROM, NOW, StatisticsDimension.CHANNEL));

        // Assert
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.deliveryRate()).isEqualTo(0.8);
            assertThat(row.inFlight()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("§11.2: the dashboard sums the channels, and an OTP-free period reports no latency")
    void dashboardRollsUpChannelsAndLeavesLatencyEmpty() {
        // Arrange
        DashboardService service = dashboardService();
        when(statistics.aggregate(any()))
                .thenReturn(List.of(
                        new StatisticsRow("SMS", 100, 80, 10, 5, 120, uzs(240)),
                        new StatisticsRow("EMAIL", 50, 40, 5, 0, 0, uzs(10))));
        when(statistics.acceptToProviderP99Millis(any(), any(), any())).thenReturn(OptionalLong.empty());
        when(dlqEntries.count(any())).thenReturn(3L);
        when(batches.search(any())).thenReturn(List.of());
        when(providers.findAllProviders()).thenReturn(List.of());
        when(killSwitch.state()).thenReturn(KillSwitchState.inactive());

        // Act
        DashboardView view = service.summary(DashboardQuery.of(FROM, NOW));

        // Assert
        assertThat(view.totals().accepted()).isEqualTo(150);
        assertThat(view.totals().delivered()).isEqualTo(120);
        assertThat(view.totals().inFlight()).isEqualTo(10);
        assertThat(view.totals().cost()).isEqualTo(uzs(250));
        assertThat(view.totals().deliveryRate()).isEqualTo(0.8);
        assertThat(view.backlog().dlqPending()).isEqualTo(3L);
        assertThat(view.otpLatencyP99Millis()).isNull();
        assertThat(view.killSwitch().active()).isFalse();
    }

    @Test
    @DisplayName("TC-01: the dashboard reports the OTP p99 when the period saw OTP traffic")
    void dashboardReportsOtpLatency() {
        // Arrange
        DashboardService service = dashboardService();
        when(statistics.aggregate(any())).thenReturn(List.of());
        when(statistics.acceptToProviderP99Millis(any(), any(), any())).thenReturn(OptionalLong.of(1_240L));
        when(dlqEntries.count(any())).thenReturn(0L);
        when(batches.search(any())).thenReturn(List.of());
        when(providers.findAllProviders()).thenReturn(List.of());
        when(killSwitch.state()).thenReturn(KillSwitchState.inactive());

        // Act
        DashboardView view = service.summary(DashboardQuery.of(FROM, NOW));

        // Assert
        assertThat(view.otpLatencyP99Millis()).isEqualTo(1_240L);
        assertThat(view.totals().accepted()).isZero();
        assertThat(view.totals().deliveryRate()).isZero();
    }

    // ---------------------------------------------------------------- fixtures

    private MessageSearchService messageSearchService() {
        return new MessageSearchService(messageSearch, new PersonalDataAccess(audit, clock), new MessageMapperImpl());
    }

    private DashboardService dashboardService() {
        return new DashboardService(
                statistics,
                providers,
                batches,
                dlqEntries,
                killSwitch,
                new StatisticsMapperImpl(),
                new BatchMapperImpl());
    }

    private static MessageDigest digest() {
        return new MessageDigest(
                MessageId.newId(),
                STREAM_ID,
                ExternalMessageId.of("EXT-1"),
                Channel.SMS,
                MessageStatus.DELIVERED,
                MSISDN,
                NOW,
                new MessageDigest.Routing(null, Channel.SMS, null, null, CorrelationId.of("corr-1"), null, 1, NOW));
    }

    private static DlqEntry dlqEntry(MessageId messageId) {
        return DlqEntry.of(messageId, RejectionReason.ATTEMPTS_EXHAUSTED, "no answer", NOW);
    }

    private static Money uzs(long amount) {
        return Money.of(BigDecimal.valueOf(amount), Currency.getInstance("UZS"));
    }
}
