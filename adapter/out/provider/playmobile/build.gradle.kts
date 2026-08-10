// SMS-провайдер (§9, §18): своя форма запроса, свой каталог ошибок, свой транслятор
// callback'ов (AR-04). Всё общее — в adapter-provider-support.

dependencies {
    api(project(":application"))
    implementation(project(":adapter:out:provider:support"))
    implementation(project(":adapter:in:callback"))
    implementation(project(":adapter:in:contract"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)

    testImplementation(testFixtures(project(":adapter:out:provider:support")))
    testImplementation(libs.wiremock.standalone)
}
