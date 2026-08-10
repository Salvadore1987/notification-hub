// Политики фильтрации из настроек деплоя (FR-5.4, SEC-05) и стаб CustomerPreferencePort (FR-8.2).

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
}
