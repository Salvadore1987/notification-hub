plugins {
    alias(libs.plugins.spring.boot)
}

// bootstrap: Spring Boot приложение, конфигурация и wiring всех адаптеров (AR-01).

dependencies {
    // Слой adapter целиком, одной строкой: композиционному корню нужны все адаптеры сразу,
    // а какие именно они есть — знает :adapter (агрегатор). Новый адаптер объявляется там.
    implementation(project(":adapter"))
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
    testImplementation(testFixtures(project(":adapter:out:provider:support")))

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

// DB-04: ключ шифрования контента обязателен и умолчания не имеет — инстанс без него не стартует,
// и в развёртывании это правильно: тихого отката на хранение контента открытым текстом быть не должно.
// Но bootRun — это локальный запуск разработчика, и требование «сначала экспортируй ключ» он
// выполняет один раз, а потом открывает новый терминал и видит отказ старта, который не имеет
// отношения к тому, чем он занят. Поэтому здесь — и только здесь — подставляется локальный ключ:
// шифрование остаётся включённым (проверяется тот же код), а jar и образ по-прежнему требуют
// настоящий ключ из секрет-хранилища. Экспортированный CONTENT_ENCRYPTION_KEY побеждает.
//
// Значение — не секрет: это base64 строки "commhub-local-development-key!!!", он защищает
// локальную одноразовую базу и не должен встречаться нигде, кроме ноутбука.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment(
        "CONTENT_ENCRYPTION_KEY",
        providers.environmentVariable("CONTENT_ENCRYPTION_KEY")
            .getOrElse("Y29tbWh1Yi1sb2NhbC1kZXZlbG9wbWVudC1rZXkhISE="),
    )
}
