package uz.hamkorbank.commhub.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Optional;
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
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
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
            "commhub.metrics.backlog-refresh-interval=1h",
            // Фиктивный провайдер выключен явно: локальный config/application.yml включает его для
            // стенда и читается в том числе отсюда, а проверяется здесь именно выключатель (ADR-0041).
            "commhub.provider.mock.enabled=false"
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
    private final SecretResolverPort secrets;

    ApplicationContextIT(ApplicationContext context, JdbcClient jdbc, SecretResolverPort secrets) {
        this.context = context;
        this.jdbc = jdbc;
        this.secrets = secrets;
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
    @DisplayName("ADR-0041: the fake provider creates no beans where it is not switched on")
    void mockProviderIsNotWiredWhenDisabled() {
        // Arrange + Act — контекст поднят с commhub.provider.mock.enabled=false, как в образе,
        // где ни этого свойства, ни файла config/application.yml вообще нет

        // Assert — код мока лежит в jar, но без включения бинов не существует
        assertThat(context.getBeanNamesForType(uz.hamkorbank.commhub.adapter.out.provider.mock.MockSmsProvider.class))
                .as("a stand provider reachable in production would be a way to send nothing and report success")
                .isEmpty();
        assertThat(context.getBeanNamesForType(
                        uz.hamkorbank.commhub.adapter.out.provider.mock.MockDeliveryReports.class))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-02/§10.1: the token decoder is wired and no local account exists")
    void authenticationIsWiredAndNoLocalUserIsGenerated() {
        // Arrange + Act — the decoder is what makes the admin chain enforceable (ADR-0037), and its
        // presence is also what stops Boot generating a default user with a password in the log.
        // A UserDetailsService reappearing here means the Hub has started storing accounts, which
        // §10.1 forbids: identity comes from the corporate SSO.

        // Assert
        assertThat(context.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtDecoder.class))
                .as("the admin chain cannot validate anything without a decoder")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.security.core.userdetails.UserDetailsService.class))
                .as("§10.1: the Hub stores no users and no passwords")
                .isEmpty();
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

    @Test
    @DisplayName("SEC-04: the resolver in a started context reads the real process environment")
    void secretsComeFromTheProcessEnvironment() {
        // Arrange: the credentials of every provider are env: references (ADR-0036), and the one link
        // a unit test cannot cover is whether the wired bean is the real System.getenv. PATH is simply
        // a variable that is always set; nothing about it is a secret.
        String path = System.getenv("PATH");
        assumeTrue(path != null && !path.isBlank(), "PATH is not set in this environment");

        // Act
        Optional<String> resolved = secrets.resolve("env:PATH");

        // Assert
        assertThat(resolved).contains(path.strip());
        assertThat(secrets.resolve("env:COMMHUB_NO_SUCH_VARIABLE")).isEmpty();
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
