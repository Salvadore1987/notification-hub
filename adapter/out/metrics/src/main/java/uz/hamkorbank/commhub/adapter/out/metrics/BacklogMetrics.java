package uz.hamkorbank.commhub.adapter.out.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * How much work is waiting rather than moving: the outbox backlog and the DLQ (OBS-01, OBS-04).
 *
 * <p>Both answer questions no counter can. "Events are being published" says nothing about the thousand
 * that are not; the age of the oldest unpublished row is what separates a busy relay from a stopped one,
 * and it is the alert that catches a broker outage before source systems notice their status stream went
 * quiet (AD-03). The DLQ depth is the second one: messages nobody looked at accumulate silently (FR-3.3).
 *
 * <p>Measured on a schedule and read from memory, not queried inside the gauge callback: a scrape must
 * never be able to put load on the database of the sending path, and Prometheus scrapes as often as it
 * likes. A refresh that fails leaves the previous value in place and logs it — a metric that throws would
 * take the whole {@code /actuator/prometheus} response with it.
 *
 * <p>The queries go straight to {@link JdbcClient} rather than through an application port. A gauge is not
 * a use case: routing it through the core would mean inventing a port whose only client is a dashboard.
 */
@Component
public class BacklogMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(BacklogMetrics.class);

    private static final String PENDING_OUTBOX = "SELECT count(*) FROM outbox_event WHERE published_at IS NULL";

    private static final String OLDEST_OUTBOX = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)
              FROM outbox_event
             WHERE published_at IS NULL
            """;

    private static final String DLQ_DEPTH = "SELECT count(*) FROM dlq_entry WHERE NOT archived AND retried_at IS NULL";

    private final JdbcClient jdbcClient;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong dlqDepth = new AtomicLong();

    public BacklogMetrics(JdbcClient jdbcClient, MeterRegistry meters) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
        Guard.notNull(meters, "meters");
        Gauge.builder(MetricNames.OUTBOX_PENDING, outboxPending, AtomicLong::get)
                .description("Outbox events waiting to be published to Kafka (AD-03)")
                .register(meters);
        Gauge.builder(MetricNames.OUTBOX_OLDEST_AGE, outboxOldestAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox event; the relay's liveness (AD-03)")
                .register(meters);
        Gauge.builder(MetricNames.DLQ_DEPTH, dlqDepth, AtomicLong::get)
                .description("Messages in the DLQ that were neither retried nor archived (FR-3.3)")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${commhub.metrics.backlog-refresh-interval:15s}")
    @Transactional(readOnly = true)
    public void refresh() {
        outboxPending.set(count(PENDING_OUTBOX, outboxPending.get()));
        outboxOldestAgeSeconds.set(count(OLDEST_OUTBOX, outboxOldestAgeSeconds.get()));
        dlqDepth.set(count(DLQ_DEPTH, dlqDepth.get()));
    }

    private long count(String sql, long previous) {
        try {
            return jdbcClient.sql(sql).query(Long.class).optional().orElse(0L);
        } catch (RuntimeException e) {
            LOG.warn("Backlog metric could not be refreshed, keeping the previous value: {}", e.toString());
            return previous;
        }
    }
}
