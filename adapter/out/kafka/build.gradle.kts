// Продюсер (acks=all, идемпотентный) и кодеки исходящих контрактов (AD-03, §6.4, NF-08);
// JSON-схемы лежат здесь же (resources/schema) — тест держит кодек и схему в ногу.
// testFixtures: KafkaBroker — общий контейнер брокера для тестов kafka-out и kafka-in.

plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.kafka)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)

    // testFixtures — отдельные конфигурации, BOM из корневого build туда не попадает.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.spring.kafka)
    testFixturesApi(libs.testcontainers.kafka)

    testImplementation(project(":adapter:out:persistence"))
    testImplementation(testFixtures(project(":adapter:out:persistence")))
    testImplementation(testFixtures(project(":adapter:out:kafka")))
    testImplementation(libs.spring.boot.starter.data.jdbc)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
