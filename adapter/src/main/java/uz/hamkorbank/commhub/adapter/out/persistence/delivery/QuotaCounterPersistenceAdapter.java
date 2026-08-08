package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.QuotaScope;
import uz.hamkorbank.commhub.application.port.out.QuotaWindow;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * {@link QuotaCounterPort} over {@code quota_counter} (FR-2.6).
 *
 * <p>Windows are cut in {@code Asia/Tashkent}, not UTC: a daily budget is a business day of the Bank,
 * and a counter that rolled over at 05:00 local time would be indefensible in a cost report (UI-04).
 *
 * <p>Registering usage increments both windows in one statement each, atomically inside the row: parallel
 * senders on several instances add up instead of overwriting each other. §10.1 leaves the door open to
 * moving these counters to Redis; the port stays the same if that happens.
 *
 * <p>An unscoped dimension is stored as a sentinel ({@code ''}, zero uuid) rather than {@code NULL}, so
 * the unique key is an ordinary one and {@code ON CONFLICT} infers it without ambiguity.
 */
@Repository
public class QuotaCounterPersistenceAdapter implements QuotaCounterPort {

    private static final ZoneId BUSINESS_ZONE = QuietHours.DEFAULT_ZONE;

    private static final String ANY_STREAM_OR_CHANNEL = "";

    private static final UUID ANY_PROVIDER = new UUID(0L, 0L);

    private static final String SELECT_USAGE = """
            SELECT counter, cost_counter, cost_currency
            FROM quota_counter
            WHERE stream_id = :streamId
              AND channel = :channel
              AND provider_id = :providerId
              AND window_type = :window
              AND period_start = :periodStart
            """;

    private static final String INCREMENT = """
            INSERT INTO quota_counter (id, stream_id, channel, provider_id, window_type, period_start,
                                       counter, cost_counter, cost_currency)
            VALUES (:id, :streamId, :channel, :providerId, :window, :periodStart, :count, :cost, :currency)
            ON CONFLICT (stream_id, channel, provider_id, window_type, period_start) DO UPDATE SET
                counter = quota_counter.counter + EXCLUDED.counter,
                cost_counter = quota_counter.cost_counter + EXCLUDED.cost_counter,
                cost_currency = COALESCE(quota_counter.cost_currency, EXCLUDED.cost_currency),
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;

    public QuotaCounterPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public QuotaConfig.Usage usage(QuotaScope scope, QuotaWindow window, Instant now) {
        return jdbcClient
                .sql(SELECT_USAGE)
                .param("streamId", streamIdOf(scope))
                .param("channel", channelOf(scope))
                .param("providerId", providerIdOf(scope))
                .param("window", window.name())
                .param("periodStart", periodStart(window, now))
                .query((rs, rowNum) -> new QuotaConfig.Usage(
                        rs.getLong("counter"), SqlValues.money(rs, "cost_counter", "cost_currency")))
                .optional()
                .orElseGet(() -> new QuotaConfig.Usage(0, null));
    }

    @Override
    @Transactional
    public void register(QuotaScope scope, long count, Money cost, Instant occurredAt) {
        for (QuotaWindow window : QuotaWindow.values()) {
            increment(scope, window, count, cost, occurredAt);
        }
    }

    private void increment(QuotaScope scope, QuotaWindow window, long count, Money cost, Instant occurredAt) {
        jdbcClient
                .sql(INCREMENT)
                .param("id", UuidV7.generate())
                .param("streamId", streamIdOf(scope))
                .param("channel", channelOf(scope))
                .param("providerId", providerIdOf(scope))
                .param("window", window.name())
                .param("periodStart", periodStart(window, occurredAt))
                .param("count", count)
                .param("cost", cost == null ? BigDecimal.ZERO : cost.amount())
                .param("currency", cost == null ? null : cost.currency().getCurrencyCode())
                .update();
    }

    private LocalDate periodStart(QuotaWindow window, Instant now) {
        LocalDate localDate = now.atZone(BUSINESS_ZONE).toLocalDate();
        return window == QuotaWindow.MONTH ? localDate.withDayOfMonth(1) : localDate;
    }

    private String streamIdOf(QuotaScope scope) {
        return scope.streamId() == null
                ? ANY_STREAM_OR_CHANNEL
                : scope.streamId().value();
    }

    private String channelOf(QuotaScope scope) {
        return scope.channel() == null ? ANY_STREAM_OR_CHANNEL : scope.channel().name();
    }

    private UUID providerIdOf(QuotaScope scope) {
        return scope.providerId() == null ? ANY_PROVIDER : scope.providerId().value();
    }
}
