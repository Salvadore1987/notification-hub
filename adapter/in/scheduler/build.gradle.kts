// Планировщики: тики use case'ов (outbox relay, выгрузка FR-6.4, здоровье провайдеров,
// обслуживание секций, свипы окон). Сами use case'ы — в application.

dependencies {
    api(project(":application"))
    implementation(project(":adapter-out-compliance"))
    implementation(project(":adapter-out-persistence"))

    implementation(libs.spring.boot.starter)
}
