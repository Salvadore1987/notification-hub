package uz.hamkorbank.commhub.application.port.in.query;

/**
 * What one row of a report counts (§11.2 "Статистика/Отчёты", FR-6.2).
 *
 * <p>An enum rather than a column name from the request: the dimension decides how the query groups and
 * which index it can use, and a report that lets the caller name the column is a report that lets the
 * caller name any column.
 */
public enum StatisticsDimension {

    /** Volumes per channel — the breakdown of the dashboard (§11.2 "Дашборд"). */
    CHANNEL,

    /** Volumes and cost per provider, which is what the tariff reconciliation is read from (FR-6.2). */
    PROVIDER,

    /** Volumes per source stream; the chargeback view. */
    STREAM,

    /** Volumes per batch, for the campaign report of §11.2 "Рассылки". */
    BATCH,

    /** One row per day of the period; the shape a chart is drawn from. */
    DAY,

    /** One row per hour; only useful over short periods, and bounded by the period the query carries. */
    HOUR
}
