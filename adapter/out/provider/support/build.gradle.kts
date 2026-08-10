// Каркас адаптеров провайдеров (PR-01, AR-04): HTTP-клиент на виртуальных потоках,
// retry + circuit breaker, троттлинг, маскирование. Контракт с executor'ом: ответ
// провайдера — ProviderAck, отсутствие ответа — ProviderCallException.
// testFixtures: общие хелперы тестов провайдеров (ProviderStubs, FixedClock).

plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":application"))
    implementation(project(":adapter:out:persistence"))

    api(libs.resilience4j.circuitbreaker)
    api(libs.resilience4j.retry)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.web)

    testFixturesApi(libs.wiremock.standalone)
}
