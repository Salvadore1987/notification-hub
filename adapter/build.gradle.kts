// Слой adapter: driving-адаптеры (rest, kafka, admin, callback) и
// driven-адаптеры (persistence, kafka, provider/*, notification).
// Зависит от application и domain, но не наоборот (AR-03, AR-04).

dependencies {
    api(project(":application"))

    // in/rest, in/admin, in/callback
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)

    // in/kafka, out/kafka
    implementation(libs.spring.kafka)

    // out/persistence (+ Flyway-миграции лежат в этом модуле: src/main/resources/db/migration)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // out/provider/* — таймауты, retry с backoff+jitter, circuit breaker на провайдера (PR-01)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)

    // Транспортный DTO <-> Command (AR-06) — только через MapStruct-мапперы
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)

    // Персистентность проверяется на настоящем PostgreSQL со схемой из Flyway (QA-03, DB-01),
    // outbox-relay — на настоящем брокере (AD-03, QA-06).
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)

    // Адаптеры провайдеров проверяются по стабам из документации Playmobile / SMS Gate (QA-04, PR-04)
    testImplementation(libs.wiremock.standalone)
}
