package uz.hamkorbank.commhub.adapter.out.persistence.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Read-only replica for the heavy analytics of the admin panel and for exports (DB-06).
 *
 * <p>Declared only when {@code commhub.persistence.read-replica.url} names one, so a developer machine
 * and the primary-only contours stay on a single data source. Reports then ask for
 * {@link AnalyticsJdbcClient} explicitly: routing by transaction attribute would send a report to the
 * replica silently and make replication lag look like missing data.
 *
 * <p>The condition is an expression rather than {@code @ConditionalOnProperty} on purpose. The
 * deployment yaml carries {@code url: ${DB_REPLICA_URL:}}, so the key is always <em>present</em> and
 * usually <em>empty</em>, and {@code @ConditionalOnProperty} treats any value other than {@code false}
 * as a yes — which is how every contour without a replica ended up building one.
 */
@Configuration
@ConditionalOnExpression("'${commhub.persistence.read-replica.url:}'.trim().length() > 0")
public class ReadReplicaConfig {

    @Bean(destroyMethod = "close")
    public ReadReplicaClient readReplicaClient(ReadReplicaProperties properties) {
        return new ReadReplicaClient(properties);
    }
}
