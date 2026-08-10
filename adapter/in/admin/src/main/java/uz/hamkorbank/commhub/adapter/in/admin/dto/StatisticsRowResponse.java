package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * One row of a report (§11.2 "Статистика/Отчёты", FR-6.2).
 *
 * @param key the value of the dimension the report grouped by — a channel, a provider code, a date
 * @param inFlight messages neither delivered, failed nor rejected; the buckets deliberately do not add
 *     up to {@code accepted}, and this is the difference
 */
public record StatisticsRowResponse(
        String key,
        long accepted,
        long delivered,
        long failed,
        long rejected,
        long inFlight,
        long segments,
        RowCost cost) {

    /** Cost of the row and the rate beside it, kept together so the CSV and the screen agree. */
    public record RowCost(String amount, double deliveryRate) {}
}
