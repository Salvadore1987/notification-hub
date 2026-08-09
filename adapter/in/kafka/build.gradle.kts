// Входящие консьюмеры (§8.1, IK-01…IK-03): класс трафика определяется топиком,
// изоляция TC-01 — раздельные фабрики и пулы на класс.

dependencies {
    api(project(":application"))
    implementation(project(":adapter:in:contract"))
    implementation(project(":adapter:out:kafka"))
    implementation(project(":adapter:observability"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.kafka)
    implementation(libs.micrometer.core)

    testImplementation(testFixtures(project(":adapter:out:kafka")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
