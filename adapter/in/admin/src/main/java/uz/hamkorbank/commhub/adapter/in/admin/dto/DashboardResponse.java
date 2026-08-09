package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * The summary screen (§11.2 "Дашборд", UI-03).
 *
 * @param otpLatencyP99Millis {@code null} when no OTP traffic fell in the period — the client has to
 *     draw "no data" rather than a zero, which on this particular tile would read as perfect latency
 */
public record DashboardResponse(
        String from,
        String to,
        TotalsResponse totals,
        List<StatisticsRowResponse> byChannel,
        List<ProviderHealthResponse> providers,
        BacklogResponse backlog,
        Long otpLatencyP99Millis,
        KillSwitchResponse killSwitch) {

    /** The period rolled up across every channel (FR-6.2). */
    public record TotalsResponse(
            long accepted,
            long delivered,
            long failed,
            long rejected,
            long inFlight,
            long segments,
            String cost,
            double deliveryRate) {}

    /** @param selectable whether the router may currently pick this provider (FR-6.3) */
    public record ProviderHealthResponse(String provider, String channel, String health, boolean selectable) {}

    /** What is not moving: the two figures that tell a busy Hub from a stopped one (OBS-01). */
    public record BacklogResponse(long dlqPending, List<BatchSummaryResponse> activeBatches) {}

    /** One active batch, enough to recognise it and click through (§11.2 "Рассылки"). */
    public record BatchSummaryResponse(
            String batchId,
            String streamId,
            String channel,
            String status,
            long total,
            long processed,
            double completionPercent,
            String createdAt) {}
}
