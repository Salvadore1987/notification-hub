package uz.hamkorbank.commhub.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The infrastructure a whole Notification Hub needs in order to start: PostgreSQL and a broker.
 *
 * <p>One container of each per JVM, shared by every bootstrap integration test. Starting them per test
 * class would dominate the run, and there is nothing in either that one test could leave behind for
 * another — the schema is built by the application's own Flyway migrations (DB-01), and a topic is
 * append-only anyway.
 *
 * <p>Unlike the persistence tests, the schema is <em>not</em> migrated here: proving that the
 * application migrates its own database on startup is part of what a context test is for.
 */
public final class HubTestContainers {

    /**
     * Content key of the test contour; base64 of 32 bytes, as AES-256 requires (DB-04).
     *
     * <p>Under the key id the deployment yaml declares, not one of the test's own: every key in the map
     * has to be usable, so an id of its own would leave the yaml's {@code k1} present and empty — which
     * is exactly the startup failure a contour gets when it forgets {@code CONTENT_ENCRYPTION_KEY}.
     */
    public static final String ENCRYPTION_KEY_ID = "k1";

    public static final String ENCRYPTION_KEY = "dGVzdC1jb250ZW50LWtleS0zMi1ieXRlcy1sb25nISE=";

    private static final String SCHEMA = "comm_hub";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
            .withDatabaseName("commhub")
            .withUsername("commhub")
            .withPassword("commhub");

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    private HubTestContainers() {}

    public static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
    }

    /** Points the application at the containers and gives it the content key it refuses to start without. */
    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", HubTestContainers::jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("commhub.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("commhub.persistence.encryption.active-key-id", () -> ENCRYPTION_KEY_ID);
        registry.add("commhub.persistence.encryption.keys." + ENCRYPTION_KEY_ID, () -> ENCRYPTION_KEY);
    }

    /** Connects straight into {@code comm_hub}, so the adapters' unqualified table names resolve. */
    public static String jdbcUrl() {
        start();
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    public static String bootstrapServers() {
        start();
        return KAFKA.getBootstrapServers();
    }
}
