// Push-провайдер (§9.4, PU-01…PU-13): свой транспорт и каталог кодов; fan-out по
// устройствам живёт выше порта (application), общее — в adapter-provider-support.

dependencies {
    api(project(":application"))
    implementation(project(":adapter:out:provider:support"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)

    testImplementation(testFixtures(project(":adapter:out:provider:support")))
    testImplementation(libs.wiremock.standalone)
}
