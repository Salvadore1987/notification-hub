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

    // Транспортный DTO <-> Command (AR-06) — только через MapStruct-мапперы
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)

    // Персистентность проверяется на настоящем PostgreSQL со схемой из Flyway (QA-03, DB-01).
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
}
