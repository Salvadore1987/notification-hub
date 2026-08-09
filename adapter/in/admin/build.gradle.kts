// Admin BFF (§11.2, SEC-02/SEC-03/SEC-08): свой API под /api/admin/v1.
// Карточки сообщения и батча отвечают телом §8.2 — отсюда зависимость от adapter-rest (dto/mapper).

dependencies {
    api(project(":application"))
    implementation(project(":adapter-in-contract"))
    implementation(project(":adapter-in-rest"))
    implementation(project(":adapter-in-security"))
    implementation(project(":adapter-in-importer"))
    implementation(project(":adapter-out-provider-support"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)
}
