package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * A source stream as the administration screen shows it (§11.2 "Входящие потоки", FR-1.3).
 *
 * @param connectionStatus whether anything has arrived from this stream lately — the column that
 *     answers "is the integration up" without opening a log
 */
public record StreamResponse(
        String streamId,
        String name,
        String integrationType,
        String status,
        String connectionStatus,
        StreamDefaultsDto defaults,
        LimitsDto limits,
        String lastActivityAt) {

    /** What a submission from this stream gets when it names nothing itself (FR-1.3). */
    public record StreamDefaultsDto(
            String channel, String provider, String trafficClass, String priority, String balancingStrategy) {}

    /** The three limits a stream carries (FR-2.6, IR-02, FR-5.3). */
    public record LimitsDto(QuotaDto quota, RateLimitDto rateLimit, QuietHoursDto quietHours) {}
}
