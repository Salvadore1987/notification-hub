// Политики конвейера из настроек деплоя: окно дедупликации (FR-1.5), бюджет отправки (PR-01),
// пороги здоровья провайдеров (FR-6.3) — та же форма, что у adapter-compliance.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
}
