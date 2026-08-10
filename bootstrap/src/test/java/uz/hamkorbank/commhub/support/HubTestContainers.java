package uz.hamkorbank.commhub.support;

import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The infrastructure a whole Notification Hub needs in order to start: PostgreSQL, a broker, Keycloak.
 *
 * <p>One container of each per JVM, shared by every bootstrap integration test. Starting them per test
 * class would dominate the run, and there is nothing in any of them that one test could leave behind
 * for another — the schema is built by the application's own Flyway migrations (DB-01), a topic is
 * append-only anyway, and the realm is read-only to the tests.
 *
 * <p>Keycloak is here rather than in the one test that checks 401s because the issuer is a startup
 * requirement now (ADR-0037): every full-context test needs one. Faking it — a static JWKS, a stub
 * discovery document — would cost the same seconds and stop {@code ApplicationContextIT} from proving
 * the thing it exists to prove, which is that a pod boots with the wiring a pod is given.
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

    private static final PostgreSQLContainer POSTGRES_DB = new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
            .withDatabaseName("commhub")
            .withUsername("commhub")
            .withPassword("commhub");

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    /** Realm of the demo contour; the same export {@code docker compose} imports. */
    public static final String REALM = "commhub";

    /**
     * Keycloak with the realm of {@code docker/keycloak/}, which the build puts on the test classpath.
     *
     * <p>{@code KC_HOSTNAME} is deliberately unset: {@code start-dev} derives the issuer from the
     * {@code Host} header, so the discovery document, the {@code iss} of every token and the
     * {@code issuer-uri} handed to the application all agree on the mapped port without being told.
     *
     * <p>The wait strategy is the realm's own discovery endpoint rather than {@code /health/ready},
     * because what a test depends on is the <em>import having finished</em>, not the port being open.
     */
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    DockerImageName.parse("quay.io/keycloak/keycloak:26.7.1"))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("commhub-realm.json"),
                    "/opt/keycloak/data/import/commhub-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private HubTestContainers() {}

    public static synchronized void start() {
        if (!POSTGRES_DB.isRunning()) {
            POSTGRES_DB.start();
        }
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
    }

    /** Points the application at the containers and gives it the content key it refuses to start without. */
    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", HubTestContainers::jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_DB::getUsername);
        registry.add("spring.datasource.password", POSTGRES_DB::getPassword);
        registry.add("commhub.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("commhub.persistence.encryption.active-key-id", () -> ENCRYPTION_KEY_ID);
        registry.add("commhub.persistence.encryption.keys." + ENCRYPTION_KEY_ID, () -> ENCRYPTION_KEY);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", HubTestContainers::issuerUri);
    }

    /** Issuer of the test realm, as both the application and the token will spell it. */
    public static String issuerUri() {
        start();
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    /** Connects straight into {@code comm_hub}, so the adapters' unqualified table names resolve. */
    public static String jdbcUrl() {
        start();
        String url = POSTGRES_DB.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    public static String username() {
        return POSTGRES_DB.getUsername();
    }

    public static String password() {
        return POSTGRES_DB.getPassword();
    }

    public static String bootstrapServers() {
        start();
        return KAFKA.getBootstrapServers();
    }

    /** The same URL with extra driver parameters — the outage tests need bounded socket timeouts. */
    public static String jdbcUrlWith(String parameters) {
        return jdbcUrl() + "&" + parameters;
    }

    /**
     * Freezes a container without losing what is in it (QA-06).
     *
     * <p>{@code docker pause} rather than {@code stop}: a stopped Testcontainers container is a removed
     * one, and an outage test has to be able to bring the same database back with the same rows in it.
     * A paused process also fails the way an outage does — connections hang rather than being refused —
     * which is the harder case and the one the timeouts exist for.
     */
    public static void pause(String container) {
        start();
        docker(container, true);
    }

    public static void unpause(String container) {
        start();
        docker(container, false);
    }

    /** Names accepted by {@link #pause} and {@link #unpause}. */
    public static final String POSTGRES = "postgres";

    public static final String KAFKA_BROKER = "kafka";

    /**
     * Idempotent on purpose: a test cleans up by unpausing both containers whether it paused them or
     * not, and Docker answers 500 when asked to unpause something that is running.
     */
    private static void docker(String container, boolean pause) {
        String id = POSTGRES.equals(container) ? POSTGRES_DB.getContainerId() : KAFKA.getContainerId();
        DockerClient client = DockerClientFactory.instance().client();
        boolean paused = Boolean.TRUE.equals(
                client.inspectContainerCmd(id).exec().getState().getPaused());
        if (pause && !paused) {
            client.pauseContainerCmd(id).exec();
        } else if (!pause && paused) {
            client.unpauseContainerCmd(id).exec();
        }
    }
}
