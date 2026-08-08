package uz.hamkorbank.commhub.adapter.out.persistence.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The connection the reporting reads of the admin panel go through (DB-06).
 *
 * <p>The heavy reads — the message list over a month of partitions, a report over a quarter — are the
 * queries DB-06 wants off the primary. They are also the ones an operator runs during an incident,
 * which is when the primary is least able to spare the buffers for them.
 *
 * <p>Deliberately <em>not</em> a second {@link JdbcClient} bean: adding one would make the injection
 * point of every other repository in this module ambiguous. It is a holder, resolved once at startup,
 * and it falls back to the primary when no replica is configured — a developer machine and the smaller
 * contours run one database. Choosing explicitly is the point of DB-06's design: routing by transaction
 * attribute would send a report to the replica silently and make replication lag look like data that had
 * gone missing.
 *
 * <p>Only queries that tolerate lag may use it. A report reading state a second old is a report; a
 * routing decision reading state a second old is a message sent over a provider that was just disabled.
 */
@Component
public class AnalyticsJdbcClient {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsJdbcClient.class);

    private final JdbcClient delegate;

    public AnalyticsJdbcClient(
            @Qualifier("jdbcClient") JdbcClient primary,
            @Qualifier("readReplicaJdbcClient") ObjectProvider<JdbcClient> replica) {
        JdbcClient configured = replica.getIfAvailable();
        this.delegate = configured == null ? primary : configured;
        if (configured == null) {
            LOG.info("No read replica configured (commhub.persistence.read-replica.url); "
                    + "admin reports read from the primary (DB-06).");
        } else {
            LOG.info("Admin reports read from the configured read replica (DB-06).");
        }
    }

    public JdbcClient client() {
        return delegate;
    }
}
