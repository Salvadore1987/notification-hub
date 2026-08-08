// Слой application: input-порты (use cases), output-порты, оркестрация, saga отправки.
// Зависит только от domain (AR-03).

dependencies {
    api(project(":domain"))

    // DI/транзакции для реализаций use case; без web/kafka/persistence-специфики.
    implementation(libs.spring.context)
    implementation(libs.spring.tx)

    // MapStruct: command <-> domain <-> DTO (правило проекта; мапперы только в mapper/)
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.mapstruct.processor)
}
