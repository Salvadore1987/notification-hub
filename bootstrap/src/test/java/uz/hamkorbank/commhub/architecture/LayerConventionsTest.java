package uz.hamkorbank.commhub.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conventions the layers are written in, as rules rather than as prose (QA-02, AR-04…AR-06).
 *
 * <p>{@link HexagonalArchitectureTest} answers "may this layer see that one". This class answers the
 * question that costs more in review: whether a new class was put where its kind lives. Naming here is
 * not cosmetic — {@code …Service} is what makes a use case implementation findable from its port,
 * {@code handlers/} is what keeps an advice from being buried in a controller, and a mapper outside
 * {@code mapper/} is mapping logic that has leaked into a use case.
 *
 * <p>Every rule below is one the project already keeps; what they add is that breaking one is a failed
 * build rather than a comment on a pull request.
 */
class LayerConventionsTest {

    private static final String BASE = "uz.hamkorbank.commhub";
    private static final String DOMAIN = BASE + ".domain..";
    private static final String APPLICATION = BASE + ".application..";
    private static final String ADAPTER = BASE + ".adapter..";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    // ------------------------------------------------------------------ ports

    @Test
    @DisplayName("AR-06: input ports are interfaces — a use case is a contract, not an implementation")
    void inputPortsAreInterfaces() {
        classes()
                .that()
                .resideInAPackage(BASE + ".application.port.in")
                .and()
                .areNotMemberClasses()
                .and(notPackageInfo())
                .should()
                .beInterfaces()
                .because("AR-06: adapters translate transport DTO into a command and call an interface")
                .check(classes);
    }

    @Test
    @DisplayName("AR-06: commands and queries are records — an input port takes values, not mutable state")
    void commandsAndQueriesAreRecords() {
        classes()
                .that()
                .resideInAnyPackage(BASE + ".application.port.in.command", BASE + ".application.port.in.query")
                .and()
                .areNotMemberClasses()
                .and()
                .areNotEnums()
                .and(notPackageInfo())
                .should()
                .beRecords()
                .check(classes);
    }

    @Test
    @DisplayName("AR-03: output ports are interfaces named …Port or …Repository")
    void outputPortsAreNamedConsistently() {
        classes()
                .that()
                .resideInAnyPackage(BASE + ".application.port.out", BASE + ".application.port.out.provider")
                .and()
                .areInterfaces()
                .and()
                .areNotMemberClasses()
                .should()
                .haveSimpleNameEndingWith("Port")
                .orShould()
                .haveSimpleNameEndingWith("Repository")
                .because("a driven port is found by its name from the adapter that implements it")
                .check(classes);
    }

    @Test
    @DisplayName("the values travelling through a port are records or enums, never classes with behaviour")
    void portValuesAreRecordsOrEnums() {
        classes()
                .that()
                .resideInAnyPackage(BASE + ".application.port.out", BASE + ".application.port.out.provider")
                .and()
                .areNotInterfaces()
                .and()
                .areNotMemberClasses()
                .and(notPackageInfo())
                .should()
                .beRecords()
                .orShould()
                .beEnums()
                .check(classes);
    }

    @Test
    @DisplayName("AR-06: a DTO is a record, an enum or a sealed contract over records")
    void dtosAreValues() {
        classes()
                .that()
                .resideInAPackage(BASE + ".application.dto")
                .and()
                .areNotMemberClasses()
                .and(notPackageInfo())
                .should()
                .beRecords()
                .orShould()
                .beEnums()
                .orShould()
                .beInterfaces()
                .check(classes);
    }

    // ------------------------------------------------------------ use cases

    @Test
    @DisplayName("a use case implementation is named …Service and lives in application.service")
    void useCaseImplementationsAreServices() {
        classes()
                .that()
                .implement(resideInAPackage(BASE + ".application.port.in"))
                .and()
                .areNotInterfaces()
                .should()
                .haveSimpleNameEndingWith("Service")
                .andShould()
                .resideInAPackage(BASE + ".application.service")
                .because("the implementation of a use case is found from its port by name (AR-06)")
                .check(classes);
    }

    @Test
    @DisplayName("orchestration only: a use case never contains mapping code of its own")
    void useCasesMapThroughMapStruct() {
        noClasses()
                .that()
                .resideInAPackage(BASE + ".application.service")
                .should()
                .haveSimpleNameEndingWith("Mapper")
                .because("mapping belongs in mapper/, behind a MapStruct interface")
                .check(classes);
    }

    // -------------------------------------------------------------- mappers

    @Test
    @DisplayName("MapStruct only: a mapper is an annotated interface in a mapper package")
    void mappersAreMapStructInterfaces() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Mapper")
                .and()
                .resideInAnyPackage(APPLICATION, ADAPTER)
                .and()
                .doNotHaveSimpleName("RowMapper")
                .and(not(nameEndsWith("RowMapper")))
                .should()
                .beInterfaces()
                .andShould()
                .resideInAPackage("..mapper..")
                .andShould()
                .beAnnotatedWith("org.mapstruct.Mapper")
                .check(classes);
    }

    @Test
    @DisplayName("a MapStruct mapper is a Spring bean — a mapper instantiated by hand is one nobody can replace")
    void mappersAreSpringComponents() {
        classes()
                .that()
                .areAnnotatedWith("org.mapstruct.Mapper")
                .should(useTheSpringComponentModel())
                .check(classes);
    }

    // ------------------------------------------------------------- adapters

    @Test
    @DisplayName("AR-04: an implementation of a driven port lives in adapter/out")
    void drivenPortsAreImplementedInOutboundAdapters() {
        classes()
                .that()
                .implement(resideInAnyPackage(BASE + ".application.port.out", BASE + ".application.port.out.provider"))
                .and()
                .areNotInterfaces()
                .and()
                .resideInAPackage(ADAPTER)
                .should()
                .resideInAPackage(BASE + ".adapter.out..")
                .because("AR-04: adding a provider or a store is a new driven adapter and nothing else")
                .check(classes);
    }

    @Test
    @DisplayName("a persistence adapter is named …PersistenceAdapter")
    void persistenceAdaptersAreNamed() {
        classes()
                .that()
                .resideInAPackage(BASE + ".adapter.out.persistence..")
                .and()
                .implement(resideInAPackage(BASE + ".application.port.out"))
                .and()
                .areNotInterfaces()
                .and(not(nameEndsWith("Repository")))
                .should()
                .haveSimpleNameEndingWith("PersistenceAdapter")
                .check(classes);
    }

    @Test
    @DisplayName("a REST endpoint is a …Controller in adapter/in")
    void controllersAreNamedAndPlaced() {
        classes()
                .that()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should()
                .haveSimpleNameEndingWith("Controller")
                .andShould()
                .resideInAPackage(BASE + ".adapter.in..")
                .check(classes);
    }

    @Test
    @DisplayName("an advice lives in handlers/, one class per concern")
    void advicesLiveInHandlerPackages() {
        classes()
                .that()
                .areAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .or()
                .areAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                .should()
                .resideInAPackage("..handlers..")
                .andShould()
                .haveSimpleNameEndingWith("Handler")
                .check(classes);
    }

    @Test
    @DisplayName("a controller talks to use cases, never to a driven adapter")
    void controllersDoNotReachIntoOutboundAdapters() {
        noClasses()
                .that()
                .resideInAPackage(BASE + ".adapter.in.rest..")
                .or()
                .resideInAPackage(BASE + ".adapter.in.admin..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(BASE + ".adapter.out.persistence..", BASE + ".adapter.out.kafka..")
                .because("an inbound adapter reaches the store through a port, not through another adapter")
                .check(classes);
    }

    // ----------------------------------------------------- global rules

    @Test
    @DisplayName("constructor injection only — no annotated fields anywhere")
    void noFieldInjection() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
                .orShould()
                .beAnnotatedWith("jakarta.inject.Inject")
                .because("a project rule: dependencies arrive through the constructor")
                .check(classes);
    }

    @Test
    @DisplayName("a bean holds no mutable state — its collaborators arrive final through the constructor")
    void beanFieldsAreFinal() {
        // Inner classes are out: a token bucket and an import counter exist in order to be mutated,
        // and they are held by one caller, not shared as a bean.
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(BASE + ".application.service..", BASE + ".adapter.in.rest..")
                .and()
                .areDeclaredInClassesThat()
                .areNotMemberClasses()
                .and()
                .areNotStatic()
                .should()
                .beFinal()
                .check(classes);
    }

    @Test
    @DisplayName("the application layer reports through MetricsPort, not through a logger")
    void theApplicationLayerHasNoLogger() {
        noClasses()
                .that()
                .resideInAPackage(APPLICATION)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.slf4j..", "java.util.logging..", "org.apache.logging..", "ch.qos.logback..")
                .because("the layer has no logging dependency on purpose — anything worth reporting "
                        + "leaves through MetricsPort (OBS-01)")
                .check(classes);
    }

    @Test
    @DisplayName("the application layer knows no transport: no web, no Kafka, no JDBC, no Jackson")
    void theApplicationLayerKnowsNoTransport() {
        noClasses()
                .that()
                .resideInAPackage(APPLICATION)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.jdbc..",
                        "org.springframework.kafka..",
                        "org.apache.kafka..",
                        "jakarta.servlet..",
                        "jakarta.mail..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..")
                .because("AR-03: transport belongs to the adapters; the core only knows ports")
                .check(classes);
    }

    @Test
    @DisplayName("the domain never reads a clock — aggregates take the instant as a parameter")
    void theDomainNeverReadsAClock() {
        noClasses()
                .that()
                .resideInAnyPackage(BASE + ".domain.model..", BASE + ".domain.service..")
                .should()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDate.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .orShould()
                .callMethod(java.time.ZonedDateTime.class, "now")
                .because("the application layer gets 'now' from ClockPort and passes it in — "
                        + "a domain that reads the clock cannot be tested at a chosen instant")
                .check(classes);
    }

    @Test
    @DisplayName("nothing writes to the console — output is a log line or a metric")
    void nothingPrintsToTheConsole() {
        noClasses()
                .that()
                .resideInAnyPackage(DOMAIN, APPLICATION, ADAPTER)
                .should()
                .accessField(System.class, "out")
                .orShould()
                .accessField(System.class, "err")
                .check(classes);
    }

    @Test
    @DisplayName("dates are java.time — the legacy calendar types stay out of the model")
    void noLegacyDateTypes() {
        noClasses()
                .that()
                .resideInAnyPackage(DOMAIN, APPLICATION)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.sql.Timestamp")
                .check(classes);
    }

    // ------------------------------------------------------------- helpers

    private static DescribedPredicate<JavaClass> resideInAPackage(String packageIdentifier) {
        return JavaClass.Predicates.resideInAPackage(packageIdentifier);
    }

    private static DescribedPredicate<JavaClass> resideInAnyPackage(String... packageIdentifiers) {
        return JavaClass.Predicates.resideInAnyPackage(packageIdentifiers);
    }

    private static DescribedPredicate<JavaClass> nameEndsWith(String suffix) {
        return DescribedPredicate.describe(
                "simple name ending with " + suffix,
                javaClass -> javaClass.getSimpleName().endsWith(suffix));
    }

    private static DescribedPredicate<JavaClass> not(DescribedPredicate<JavaClass> predicate) {
        return DescribedPredicate.not(predicate);
    }

    /** {@code package-info} is a class to ArchUnit and a comment to everyone else. */
    private static DescribedPredicate<JavaClass> notPackageInfo() {
        return DescribedPredicate.describe(
                "are not package-info", javaClass -> !javaClass.getSimpleName().equals("package-info"));
    }

    /**
     * MapStruct's {@code componentModel} is read off the annotation rather than off the generated class:
     * the implementation is produced by the annotation processor, and a test that looked for it would
     * pass or fail depending on whether the processor had run.
     */
    private static ArchCondition<JavaClass> useTheSpringComponentModel() {
        return new ArchCondition<>("declare componentModel = \"spring\"") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Object componentModel = item.getAnnotationOfType("org.mapstruct.Mapper")
                        .get("componentModel")
                        .orElse(null);
                boolean spring = "spring".equals(componentModel);
                events.add(new SimpleConditionEvent(
                        item, spring, "%s declares componentModel = %s".formatted(item.getName(), componentModel)));
            }
        };
    }
}
