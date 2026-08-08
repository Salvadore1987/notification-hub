package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;

/**
 * Reports over the sends of a period (§11.2 "Статистика/Отчёты", FR-6.2).
 *
 * <p>One method, because a report and its export are the same query — an export that reads differently
 * from the screen is an export nobody can reconcile with what they saw. The file format is a rendering
 * and belongs to the adapter.
 */
public interface GetStatistics {

    List<StatisticsRowView> report(StatisticsQuery query);
}
