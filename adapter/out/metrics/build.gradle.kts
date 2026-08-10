// Единственная реализация MetricsPort (OBS-01) плюс метрики-состояния:
// breaker'ы адаптеров и backlog (что не движется), считанные по расписанию.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.micrometer.core)
    implementation(libs.resilience4j.circuitbreaker)
}
