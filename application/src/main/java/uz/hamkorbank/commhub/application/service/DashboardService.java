package uz.hamkorbank.commhub.application.service;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.dto.DashboardView;
import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.mapper.BatchMapper;
import uz.hamkorbank.commhub.application.mapper.StatisticsMapper;
import uz.hamkorbank.commhub.application.port.in.GetDashboard;
import uz.hamkorbank.commhub.application.port.in.query.BatchListQuery;
import uz.hamkorbank.commhub.application.port.in.query.DashboardQuery;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsDimension;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StatisticsPort;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The summary screen of the admin panel (§11.2 "Дашборд", UI-03).
 *
 * <p>Assembled here rather than in the BFF because it is one answer to one question, and the BFF is not
 * allowed to know that "delivery rate" is delivered over accepted or that "active" is four batch
 * statuses. What the panel gets is a screen; what it does with it is layout.
 *
 * <p>How many active batches the screen shows is bounded here and not by the caller. The dashboard is a
 * summary: an incident that leaves nine hundred batches running must not turn the polled screen into the
 * heaviest query in the system at the moment somebody needs to look at it. The batch list is next door
 * and pages properly.
 */
@Service
public class DashboardService implements GetDashboard {

    /** Enough to see what is running; the batch screen is where the rest is. */
    private static final int ACTIVE_BATCH_LIMIT = 20;

    private final StatisticsPort statistics;
    private final ProviderConfigRepository providers;
    private final BatchRepository batches;
    private final DlqRepository dlqEntries;
    private final KillSwitchPort killSwitch;
    private final StatisticsMapper statisticsMapper;
    private final BatchMapper batchMapper;

    public DashboardService(
            StatisticsPort statistics,
            ProviderConfigRepository providers,
            BatchRepository batches,
            DlqRepository dlqEntries,
            KillSwitchPort killSwitch,
            StatisticsMapper statisticsMapper,
            BatchMapper batchMapper) {
        this.statistics = Guard.notNull(statistics, "statistics");
        this.providers = Guard.notNull(providers, "providers");
        this.batches = Guard.notNull(batches, "batches");
        this.dlqEntries = Guard.notNull(dlqEntries, "dlqEntries");
        this.killSwitch = Guard.notNull(killSwitch, "killSwitch");
        this.statisticsMapper = Guard.notNull(statisticsMapper, "statisticsMapper");
        this.batchMapper = Guard.notNull(batchMapper, "batchMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardView summary(DashboardQuery query) {
        Guard.notNull(query, "query");
        List<StatisticsRowView> byChannel = statisticsMapper.toViews(statistics.aggregate(new StatisticsQuery(
                query.from(), query.to(), StatisticsDimension.CHANNEL, null, null, null, null, query.includeTest())));
        OptionalLong otpLatency =
                statistics.acceptToProviderP99Millis(query.from(), query.to(), TrafficClass.CRITICAL_OTP);
        return new DashboardView(
                query.from(),
                query.to(),
                statisticsMapper.toTotals(byChannel),
                byChannel,
                providers.findAllProviders().stream()
                        .map(statisticsMapper::toHealthLine)
                        .toList(),
                new DashboardView.Backlog(dlqEntries.count(DlqQuery.pending()), activeBatches(query)),
                otpLatency.isPresent() ? otpLatency.getAsLong() : null,
                KillSwitchResult.of(killSwitch.state()));
    }

    private List<BatchView> activeBatches(DashboardQuery query) {
        return batches.search(BatchListQuery.active(query.from(), query.to(), ACTIVE_BATCH_LIMIT)).stream()
                .map(batchMapper::toView)
                .toList();
    }
}
