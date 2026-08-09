// Провайдерские callback'и статусов (PM-02, SG-04, SEC-07). Endpoint провайдеро-независим;
// трансляторы (ProviderCallbackTranslator) живут в модулях провайдеров (AR-04).

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter.web)

    testImplementation(project(":adapter-in-rest"))
    testImplementation(libs.spring.boot.starter.test)
}
