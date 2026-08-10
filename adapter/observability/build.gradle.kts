// Ни driving, ни driven (FR-8.6, OBS-02, OBS-03): correlation id, MDC вокруг use case'а,
// маскирование PII в отрендеренной строке лога.

dependencies {
    implementation(project(":domain"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.micrometer.tracing)

    testImplementation(libs.spring.boot.starter.test)
}
