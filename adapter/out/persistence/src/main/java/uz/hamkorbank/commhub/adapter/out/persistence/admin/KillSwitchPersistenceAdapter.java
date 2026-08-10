package uz.hamkorbank.commhub.adapter.out.persistence.admin;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.config.ConfigurationCacheProperties;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.KillSwitchState;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link KillSwitchPort} over the single-row {@code kill_switch} table (FR-3.2, AD-07).
 *
 * <p>State in the database, not in the JVM, because "stopped" has to mean the same thing on every
 * instance: a Hub that learned about the switch from its own memory is a Hub that keeps sending while
 * the others stand still.
 *
 * <p>Cached for the same reason and with the same window as the routing configuration: this is read on
 * <em>every</em> submission and again on every dispatch (FR-1.7, TC-01), and a query per message in
 * front of the OTP path would spend the whole latency budget answering a question whose answer changes
 * a few times a year. A local write clears the local snapshot at once, other instances see the change
 * within {@code commhub.config.cache.refresh-interval}, which NF-07 caps at 30 seconds.
 *
 * <p>The expiry clock is {@link System#nanoTime()} so that a snapshot cannot outlive its window because
 * NTP corrected the wall clock underneath it.
 *
 * <p>An unreadable table does <b>not</b> fall back to "not stopped". Everywhere else in this system a
 * failed configuration read falls back to the deployed default, because a limiter must never become the
 * reason messages stop; here the polarity is reversed — guessing "nothing is stopped" would resume
 * sending during exactly the incident the switch was flipped for — so the read fails and the submission
 * fails with it.
 */
@Repository
public class KillSwitchPersistenceAdapter implements KillSwitchPort {

    private static final String SELECT =
            "SELECT active, includes_critical_otp, changed_at, changed_by, reason FROM kill_switch WHERE id";

    private static final String UPDATE = """
            UPDATE kill_switch
               SET active = :active,
                   includes_critical_otp = :includesCriticalOtp,
                   changed_at = :changedAt,
                   changed_by = :changedBy,
                   reason = :reason
             WHERE id
            """;

    private final JdbcClient jdbcClient;
    private final ConfigurationCacheProperties properties;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public KillSwitchPersistenceAdapter(JdbcClient jdbcClient, ConfigurationCacheProperties properties) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
        this.properties = Guard.notNull(properties, "properties");
    }

    @Override
    @Transactional(readOnly = true)
    public KillSwitchState state() {
        if (!properties.enabled()) {
            return read();
        }
        Snapshot current = snapshot.get();
        if (current == null || current.isExpired(properties.refreshInterval().toNanos())) {
            current = new Snapshot(read(), System.nanoTime());
            snapshot.set(current);
        }
        return current.state();
    }

    @Override
    @Transactional
    public void update(KillSwitchState state) {
        Guard.notNull(state, "state");
        jdbcClient
                .sql(UPDATE)
                .param("active", state.active())
                .param("includesCriticalOtp", state.includesCriticalOtp())
                .param("changedAt", SqlValues.timestamp(state.changedAt()))
                .param("changedBy", state.changedBy())
                .param("reason", state.reason())
                .update();
        snapshot.set(null);
    }

    /**
     * The row, or the inactive state when V14 has not seeded it.
     *
     * <p>Empty and inactive are the same thing here — nobody has ever flipped the switch — which is why
     * a missing row is not an error while an unreadable table is.
     */
    private KillSwitchState read() {
        return jdbcClient
                .sql(SELECT)
                .query((rs, rowNum) -> new KillSwitchState(
                        rs.getBoolean("active"),
                        rs.getBoolean("includes_critical_otp"),
                        SqlValues.instant(rs, "changed_at"),
                        rs.getString("changed_by"),
                        rs.getString("reason")))
                .optional()
                .orElseGet(KillSwitchState::inactive);
    }

    private record Snapshot(KillSwitchState state, long loadedAtNanos) {

        boolean isExpired(long ttlNanos) {
            return System.nanoTime() - loadedAtNanos >= ttlNanos;
        }
    }
}
