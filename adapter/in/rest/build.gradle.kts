// Синхронный API систем-источников (§8.2, IR-01…IR-03): контроллеры, problem+json,
// пер-стримовые лимиты. Контракт OpenAPI лежит здесь же (resources/openapi).

dependencies {
    api(project(":application"))
    implementation(project(":adapter:in:contract"))
    implementation(project(":adapter:in:security"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)
}
