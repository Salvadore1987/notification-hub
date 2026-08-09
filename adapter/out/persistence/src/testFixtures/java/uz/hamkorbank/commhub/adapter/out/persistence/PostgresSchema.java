package uz.hamkorbank.commhub.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL the persistence tests run against: one container per JVM, migrated by Flyway.
 *
 * <p>Singleton rather than {@code @Testcontainers}-managed per class: the schema is expensive to build
 * and identical for every test, and Ryuk removes the container when the JVM ends.
 */
public final class PostgresSchema {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16");
    private static final String SCHEMA = "comm_hub";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("commhub")
            .withUsername("commhub")
            .withPassword("commhub");

    private static boolean migrated;

    private PostgresSchema() {}

    public static synchronized void start() {
        if (migrated) {
            return;
        }
        CONTAINER.start();
        Flyway.configure()
                .dataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
        migrated = true;
    }

    /** Connects straight into {@code comm_hub}, so the adapters' unqualified table names resolve. */
    public static String jdbcUrl() {
        String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }
}
