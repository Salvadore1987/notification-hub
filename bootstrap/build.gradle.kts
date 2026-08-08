plugins {
    alias(libs.plugins.spring.boot)
}

// bootstrap: Spring Boot приложение, конфигурация и wiring всех адаптеров (AR-01).

dependencies {
    implementation(project(":adapter"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)

    // OBS-01: реестр Prometheus подключается на уровне развёртывания — код метрик знает только
    // MeterRegistry, а какой это реестр, решает сборка приложения.
    runtimeOnly(libs.micrometer.registry.prometheus)

    // OBS-02: мост Micrometer -> OpenTelemetry и OTLP-экспортёр. Тоже runtime: адаптеры пишут
    // против API Micrometer Tracing, а SDK подставляется здесь (Boot настраивает его сам).
    runtimeOnly(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.opentelemetry.exporter.otlp)

    // NF-05: health-индикатор брокера спрашивает метаданные у того же продюсера, что и relay,
    // поэтому bootstrap компилируется против клиента Kafka. Сами адаптеры остаются в adapter.
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)

    // ArchUnit видит классы всех слоёв через runtime classpath (AR-03, QA-02)
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("notification-hub.jar")
}
