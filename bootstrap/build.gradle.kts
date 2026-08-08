plugins {
    alias(libs.plugins.spring.boot)
}

// bootstrap: Spring Boot приложение, конфигурация и wiring всех адаптеров (AR-01).

dependencies {
    implementation(project(":adapter"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)

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
