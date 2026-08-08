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
 * Enforces the hexagonal rules of the Notification Hub (AR-02, AR-03, QA-02).
 *
 * <p>Phase 1 covers the layering and the framework-freedom of the domain. Finer-grained rules
 * (naming of use cases, ports, mappers) are added in Phase 15.
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
            "jakarta.persistence..",
            "jakarta.validation..",
            "javax.persistence..",
            "org.apache.kafka..",
            "com.fasterxml.jackson..",
            "org.hibernate..",
            "org.slf4j..",
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
}
