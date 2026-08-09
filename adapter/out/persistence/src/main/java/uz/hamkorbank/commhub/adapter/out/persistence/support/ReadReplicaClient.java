package uz.hamkorbank.commhub.adapter.out.persistence.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The replica's own connection pool and the client over it (DB-06).
 *
 * <p>A type of its own rather than a second {@link JdbcClient} bean, and a pool of its own rather than a
 * second {@code DataSource} bean. Both are the same decision: Spring Boot configures the primary
 * {@code JdbcTemplate} only while there is a single {@code DataSource} candidate, and the
 * {@code JdbcClient} only while there is none already — so declaring the replica in either of those types
 * would replace "reports read from the replica" with "the application does not start".
 *
 * <p>The pool is read-only, which turns an accidental write into a driver error rather than into a write
 * attempted against a standby.
 */
public final class ReadReplicaClient implements AutoCloseable {

    private static final String POOL_NAME = "commhub-replica-pool";

    private final HikariDataSource dataSource;
    private final JdbcClient client;

    public ReadReplicaClient(ReadReplicaProperties properties) {
        this.dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setReadOnly(true);
        dataSource.setPoolName(POOL_NAME);
        this.client = JdbcClient.create(dataSource);
    }

    public JdbcClient client() {
        return client;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
