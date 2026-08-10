package uz.hamkorbank.commhub.adapter.out.persistence.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the read-only replica of DB-06 lives, when there is one.
 *
 * <p>Bound as its own record rather than onto a {@code DataSource} bean: the replica pool is
 * deliberately not a bean of type {@code DataSource}, because a second one of those makes Spring Boot's
 * {@code JdbcTemplateAutoConfiguration} see two candidates and quietly stop configuring the primary —
 * the whole application then fails to start on a setting that was meant to speed up reports.
 *
 * @param url JDBC URL of the replica; empty means the contour runs a single node and reports read from
 *     the primary
 * @param username login on the replica; defaults to the primary's in the deployment yaml
 * @param password its password, delivered by the platform's secret store (SEC-04)
 */
@ConfigurationProperties("commhub.persistence.read-replica")
public record ReadReplicaProperties(String url, String username, String password) {

    public ReadReplicaProperties {
        url = url == null || url.isBlank() ? null : url.trim();
    }

    /** Whether a replica is configured at all; an empty URL is how the yaml says "no replica". */
    public boolean isConfigured() {
        return url != null;
    }
}
