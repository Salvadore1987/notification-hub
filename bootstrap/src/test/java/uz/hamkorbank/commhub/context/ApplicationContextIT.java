package uz.hamkorbank.commhub.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.support.HubTestContainers;

/**
 * The whole application context, started the way a pod starts it (QA-03, NF-05, DB-01).
 *
 * <p>Every other test in the project builds the slice it needs. This one builds none: it runs
 * {@link NotificationHubApplication} against a real PostgreSQL and a real broker with the deployment
 * defaults, which is the only way to find the failures that only a full context has — a bean that two
 * adapters both claim, a {@code @ConfigurationProperties} record the binder cannot fill, a migration
 * that runs from the application but not from the test harness.
 *
 * <p>It also answers the question the modules cannot answer separately: whether every driven port has
 * somebody behind it. Exactly one does not — {@code AudienceResolverPort}, the reservation for FR-8.11,
 * which is out of scope. Any second one is a port somebody wrote and nobody implemented, and it should
 * fail this test until it has an adapter.
 *
 * <p>The channel ports of {@code port.out.provider} are deliberately outside that count: they exist once
 * per enabled provider, and this context enables none. {@link ProviderAdaptersContextIT} is where they
 * are checked, with every adapter of §9 switched on.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            // Проверяется старт контекста, а не работа фоновых заданий: интервалы отодвинуты так,
            // чтобы ни одно из них не успело сработать за время теста.
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.config.cache.refresh-interval=30s",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ApplicationContextIT {

    /** The port that is declared and deliberately not implemented; see the class comment. */
    private static final Set<String> UNIMPLEMENTED_BY_DESIGN =
            Set.of("uz.hamkorbank.commhub.application.port.out.AudienceResolverPort");

    private static final String PORT_IN = "uz.hamkorbank.commhub.application.port.in";
    private static final String PORT_OUT = "uz.hamkorbank.commhub.application.port.out";

    private final ApplicationContext context;
    private final JdbcClient jdbc;

    ApplicationContextIT(ApplicationContext context, JdbcClient jdbc) {
        this.context = context;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
    }

    @Test
    @DisplayName("QA-03: the context starts with the deployment defaults")
    void theContextStarts() {
        // Arrange + Act — the context is the act; getting here means it came up

        // Assert
        assertThat(context.getBeanDefinitionCount()).isPositive();
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("notification-hub");
    }

    @Test
    @DisplayName("DB-01: the application migrates its own schema on startup")
    void flywayRunsFromTheApplication() {
        // Arrange
        String sql = "SELECT count(*) FROM comm_hub.flyway_schema_history WHERE success AND type <> 'SCHEMA'";

        // Act
        Integer applied = jdbc.sql(sql).query(Integer.class).single();

        // Assert
        assertThat(applied).isPositive();
        assertThat(jdbc.sql("SELECT to_regclass('comm_hub.message')")
                        .query(String.class)
                        .single())
                .isEqualTo("message");
    }

    @Test
    @DisplayName("AR-06: every use case of port/in is wired")
    void everyUseCaseHasAnImplementation() {
        // Arrange
        List<String> useCases = interfacesOf(PORT_IN);

        // Act
        List<String> unwired = useCases.stream().filter(name -> !hasBean(name)).toList();

        // Assert
        assertThat(useCases).isNotEmpty();
        assertThat(unwired).as("input ports without an implementation").isEmpty();
    }

    @Test
    @DisplayName("AR-03: every driven port is implemented, except the two that are placeholders")
    void everyDrivenPortHasAnAdapter() {
        // Arrange
        List<String> ports = interfacesOf(PORT_OUT);

        // Act
        List<String> unimplemented =
                ports.stream().filter(name -> !hasBean(name)).toList();

        // Assert
        assertThat(ports).isNotEmpty();
        assertThat(unimplemented)
                .as("a port without an adapter is a use case that cannot run in production")
                .containsExactlyInAnyOrderElementsOf(UNIMPLEMENTED_BY_DESIGN);
    }

    @Test
    @DisplayName("NF-05: liveness knows nothing external, readiness knows the database")
    void healthGroupsAreWiredAsDeployed() {
        // Arrange
        String liveness = context.getEnvironment().getProperty("management.endpoint.health.group.liveness.include");
        String readiness = context.getEnvironment().getProperty("management.endpoint.health.group.readiness.include");

        // Act + Assert — restarting a pod because PostgreSQL is down replaces an outage with a crash
        // loop during it; an uncommitted submission, on the other hand, is an unaccepted one (AD-03).
        assertThat(liveness).isEqualTo("livenessState");
        assertThat(readiness).contains("db");
        assertThat(liveness).doesNotContain("db");
    }

    private boolean hasBean(String className) {
        try {
            return context.getBeanNamesForType(Class.forName(className)).length > 0;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("port " + className + " is on no classpath", e);
        }
    }

    /**
     * The ports as declared, read from the class files rather than from the context.
     *
     * <p>Asking the context which ports exist would only ever list the ones it managed to wire, which is
     * the opposite of the question.
     */
    private static List<String> interfacesOf(String packageName) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packageName);
        return classes.stream()
                .filter(JavaClass::isInterface)
                .filter(javaClass -> javaClass.getPackageName().equals(packageName))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }
}
