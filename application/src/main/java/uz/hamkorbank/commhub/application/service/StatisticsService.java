package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.mapper.StatisticsMapper;
import uz.hamkorbank.commhub.application.port.in.GetStatistics;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.application.port.out.StatisticsPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reports over the sends of a period (§11.2 "Статистика/Отчёты", FR-6.2, FR-7.4).
 *
 * <p>Thin on purpose. The counting happens in the database — a report over a month of a partitioned
 * table is an aggregate, not a stream of rows through the application — and what is left here is the
 * decision of which query to run, which the query record already carries.
 */
@Service
public class StatisticsService implements GetStatistics {

    private final StatisticsPort statistics;
    private final StatisticsMapper mapper;

    public StatisticsService(StatisticsPort statistics, StatisticsMapper mapper) {
        this.statistics = Guard.notNull(statistics, "statistics");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatisticsRowView> report(StatisticsQuery query) {
        Guard.notNull(query, "query");
        return mapper.toViews(statistics.aggregate(query));
    }
}
