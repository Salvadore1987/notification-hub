// Общая модель вызывающего (SEC-01, SEC-03): AuthenticatedCaller, роли §10.1 и проверка
// доступа к потоку. Используется и REST-адаптером систем-источников, и admin BFF;
// сами security-цепочки собирает bootstrap (WebSecurityConfig) — это wiring, а не адаптер.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
}
