package uz.hamkorbank.commhub.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the hexagonal rules of the Notification Hub (AR-02, AR-03, AR-04, QA-02).
 *
 * <p>This class holds the rules about who may see whom: the layering, the framework-freedom of the
 * domain, and the self-containment of a provider adapter that AR-04 turns into a promise — adding a
 * provider is a new package and nothing else. The conventions about where a kind of class lives and
 * what it is called are in {@link LayerConventionsTest}.
 */
class HexagonalArchitectureTest {

    private static final String BASE_PACKAGE = "uz.hamkorbank.commhub";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        // Классы domain/application/adapter приходят на test runtime classpath как jar'ы модулей,
        // поэтому DO_NOT_INCLUDE_JARS здесь не используется.
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    @DisplayName("AR-03: dependencies point inward only (adapter -> application -> domain)")
    void dependenciesPointInward() {
        // Arrange
        Architectures.LayeredArchitecture rule = Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(BASE_PACKAGE + "..")
                .layer("Domain")
                .definedBy(BASE_PACKAGE + ".domain..")
                .layer("Application")
                .definedBy(BASE_PACKAGE + ".application..")
                .layer("Adapter")
                .definedBy(BASE_PACKAGE + ".adapter..")
                .layer("Bootstrap")
                .definedBy(BASE_PACKAGE + ".bootstrap..")
                .whereLayer("Bootstrap")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapter")
                .mayOnlyBeAccessedByLayers("Bootstrap")
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Adapter", "Bootstrap")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Adapter", "Bootstrap");

        // Act + Assert
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    @DisplayName("AR-02: domain has no compile dependencies on Spring, JPA, Kafka, Jackson")
    void domainStaysFrameworkFree() {
        // Arrange
        String[] forbiddenPackages = {
            "org.springframework..",
            "jakarta..",
            "javax..",
            "org.apache.kafka..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "org.hibernate..",
            "org.slf4j..",
            "org.mapstruct..",
            "io.micrometer..",
            "io.github.resilience4j..",
        };

        // Act + Assert
        noClasses()
                .that()
                .resideInAPackage(BASE_PACKAGE + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(forbiddenPackages)
                .because("AR-02: the domain module may only depend on the JDK and its own value objects")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("AR-04: a provider adapter is self-contained — nothing outside its package names it")
    void providerAdaptersStaySelfContained() {
        // Arrange — every concrete provider of §9, one package each.
        String[] providers = {"playmobile", "smsgate", "smtp", "fcm", "apns"};

        // Act + Assert
        for (String provider : providers) {
            String providerPackage = BASE_PACKAGE + ".adapter.out.provider." + provider + "..";
            noClasses()
                    .that()
                    .resideOutsideOfPackage(providerPackage)
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(providerPackage)
                    .because("AR-04: swapping a provider must touch that provider's package and nothing else — "
                            + "its callback translator and its error table live next to it, and the rest of "
                            + "the Hub reaches it through SmsProviderPort/EmailProviderPort/PushProviderPort")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Test
    @DisplayName("AR-04: the shared provider framework knows no concrete provider")
    void theProviderFrameworkKnowsNoProvider() {
        // Arrange + Act + Assert
        noClasses()
                .that()
                .resideInAPackage(BASE_PACKAGE + ".adapter.out.provider.support..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(BASE_PACKAGE + ".adapter.out.provider.*.")
                .because("timeouts, retry, breaker and throttling are the same for every provider; "
                        + "the moment the framework names one, the next one needs a change in it")
                .allowEmptyShould(true)
                .check(classes);
    }
}
