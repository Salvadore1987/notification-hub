package uz.hamkorbank.commhub.adapter.out.persistence.support;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read-only replica for heavy analytics of the admin panel and for exports (DB-06).
 *
 * <p>Declared only when {@code commhub.persistence.read-replica.url} is set, so a developer machine and
 * the primary-only environments stay on a single data source. Reports then ask for the
 * {@code readReplicaJdbcClient} bean explicitly: routing by transaction attribute would send a report
 * to the replica silently and make replication lag look like missing data.
 *
 * <p>The pool is marked read-only, which makes any accidental write fail at the driver rather than on
 * the replica.
 */
@Configuration
@ConditionalOnProperty("commhub.persistence.read-replica.url")
public class ReadReplicaConfig {

    @Bean
    @ConfigurationProperties("commhub.persistence.read-replica")
    public DataSource readReplicaDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setReadOnly(true);
        dataSource.setPoolName("commhub-replica-pool");
        return dataSource;
    }

    @Bean
    public JdbcClient readReplicaJdbcClient(DataSource readReplicaDataSource) {
        return JdbcClient.create(readReplicaDataSource);
    }
}
