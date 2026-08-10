// Секрет-хранилище (SEC-04, SG-04, ADR-0036): ссылки env:/prop: с модификатором base64: для блобов,
// TTL-кэш — чтобы разрешение ссылки не повторялось на каждое сообщение. Hub не ходит в Vault сам:
// платформа кладёт значение в окружение пода, ротация — rolling restart.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)

    testImplementation(testFixtures(project(":adapter:out:provider:support")))
    testImplementation(libs.spring.boot.starter.test)
}
