plugins {
    alias(libs.plugins.spring.boot)
}

// bootstrap: Spring Boot приложение, конфигурация и wiring всех адаптеров (AR-01).

dependencies {
    implementation(project(":adapter-in-admin"))
    implementation(project(":adapter-out-provider-apns"))
    implementation(project(":adapter-in-callback"))
    implementation(project(":adapter-out-compliance"))
    implementation(project(":adapter-in-contract"))
    implementation(project(":adapter-out-provider-fcm"))
    implementation(project(":adapter-in-importer"))
    implementation(project(":adapter-in-kafka"))
    implementation(project(":adapter-out-kafka"))
    implementation(project(":adapter-out-metrics"))
    implementation(project(":adapter-observability"))
    implementation(project(":adapter-out-persistence"))
    implementation(project(":adapter-out-provider-playmobile"))
    implementation(project(":adapter-out-policy"))
    implementation(project(":adapter-out-provider-support"))
    implementation(project(":adapter-in-rest"))
    implementation(project(":adapter-in-scheduler"))
    implementation(project(":adapter-out-secret"))
    implementation(project(":adapter-in-security"))
    implementation(project(":adapter-out-provider-smsgate"))
    implementation(project(":adapter-out-provider-smtp"))
    implementation(project(":adapter-out-time"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)

    // WebSecurityConfig переехал сюда из adapter/in/rest/security: он собирает цепочки
    // rest + admin + callback разом — это wiring композиционного корня, а не адаптер.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

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
    // Ассерты приёмочных сценариев читают таблицы напрямую: проверяется, что записал адаптер,
    // а не что вернул тот же адаптер (QA-03, QA-08).
    testImplementation(libs.spring.boot.starter.data.jdbc)
    // Приёмочные сценарии ходят в Модуль по HTTP, как система-источник: RestClient, а не MockMvc —
    // §8.2 обещан по сети, и сериализация с кодами ответов входят в обещание (QA-08).
    testImplementation(libs.spring.boot.starter.web)

    // Кросс-модульные тесты провайдеров (ProviderDocumentationContractTest,
    // ProviderRuntimeSettingsTest) живут здесь: они видят сразу несколько адаптеров.
    testImplementation(testFixtures(project(":adapter-out-provider-support")))

    // ArchUnit видит классы всех слоёв через runtime classpath (AR-03, QA-02)
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // QA-03/QA-08: полный контекст поднимается на настоящем брокере — входящие консьюмеры и relay
    // без него не стартуют, а именно их сборка и проверяется.
    testImplementation(libs.testcontainers.kafka)
    // QA-04/QA-08: провайдерские стабы для приёмочных сценариев (WireMock со своим Jetty внутри).
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("notification-hub.jar")
}
