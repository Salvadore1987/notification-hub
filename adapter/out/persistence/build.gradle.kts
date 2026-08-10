// PostgreSQL-адаптеры портов (AR-02, DB-01…DB-06) и Flyway-миграции (resources/db/migration).
// testFixtures: AbstractPersistenceIT/PostgresSchema — общий контейнер PostgreSQL со схемой
// из Flyway для интеграционных тестов персистентности (их наследует и OutboxRelayIT в kafka-out).

plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // testFixtures — отдельные конфигурации, BOM из корневого build туда не попадает.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(project(":adapter:out:time"))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.spring.boot.starter.data.jdbc)
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.flyway.core)
    testFixturesRuntimeOnly(libs.flyway.postgresql)

    testImplementation(testFixtures(project(":adapter:out:persistence")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
}
