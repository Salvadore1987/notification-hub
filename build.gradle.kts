plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spring.boot) apply false
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String): Provider<MinimalExternalModuleDependency> = catalog.findLibrary(alias).orElseThrow()

fun version(alias: String): String = catalog.findVersion(alias).orElseThrow().requiredVersion

allprojects {
    // Группа выводится из пути проекта, а не задаётся константой: имя Gradle-модуля — это
    // последний сегмент пути, поэтому :adapter:in:kafka и :adapter:out:kafka назывались бы
    // одинаково («kafka»), и координаты group:name у них совпали бы. Gradle различает проекты
    // по координатам — при совпадении зависимость (в том числе testFixtures) разрешается в
    // произвольный из двух. Путь в группе делает пару различимой: uz.hamkorbank.commhub.adapter.in
    // против uz.hamkorbank.commhub.adapter.out.
    group = "uz.hamkorbank.commhub" + project.path.substringBeforeLast(':').replace(':', '.')
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    val sourceSets = extensions.getByType<SourceSetContainer>()

    // Имя артефакта — тоже полный путь: иначе adapter/in/kafka и adapter/out/kafka дали бы два
    // файла kafka-0.1.0-SNAPSHOT.jar, и сборка fat jar'а упала бы на дубликате (а с политикой
    // «брать первый» — что хуже — собралась бы, потеряв половину классов одного из адаптеров).
    extensions.configure<BasePluginExtension> {
        archivesName.set(path.removePrefix(":").replace(':', '-'))
    }

    // Java 25 (LTS) — Virtual Threads (Loom) доступны без preview-флагов (AR-07, SRS §3.1).
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(version("java")))
        }
    }

    dependencies {
        add("implementation", platform(lib("spring-boot-bom")))
        add("annotationProcessor", platform(lib("spring-boot-bom")))
        add("testImplementation", platform(lib("spring-boot-bom")))
        add("testAnnotationProcessor", platform(lib("spring-boot-bom")))

        add("testImplementation", lib("junit-jupiter"))
        add("testImplementation", lib("assertj"))
        add("testImplementation", lib("mockito-junit-jupiter"))
        add("testRuntimeOnly", lib("junit-platform-launcher"))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    // Юнит-тесты: всё, кроме помеченного тегом "integration" (те требуют Docker).
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration")
        }
        testLogging {
            events("failed")
        }
    }

    // Интеграционные тесты (Testcontainers: PostgreSQL, Kafka; WireMock) — QA-03.
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests (Testcontainers, requires a running Docker daemon)."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("integration")
        }
        shouldRunAfter(tasks.named("test"))
        testLogging {
            events("failed")
        }
    }

    // Покрытие (QA-01): отчёт по юнит-тестам; порог для domain задан ниже.
    tasks.named<Test>("test") {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = version("checkstyle")
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            palantirJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        format("gradleKts") {
            target("*.gradle.kts")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

// QA-01: доменная логика и use cases — не менее 80% покрытия строк юнит-тестами.
listOf(":domain", ":application").forEach { module ->
    project(module) {
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("test"))
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }
    }
}
