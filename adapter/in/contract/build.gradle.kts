// Разбор входящего контракта IK-03 (§8.2), общий для REST и Kafka (IR-03), плюс словарь
// действий над батчами (BatchActions), общий для трёх входных адаптеров.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
    // api: кодеки контракта отдают JsonNode (сырые блоки IK-03) — тип виден потребителям.
    api(libs.jackson.databind)

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
}
