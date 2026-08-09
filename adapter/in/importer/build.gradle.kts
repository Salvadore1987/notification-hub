// Разовые импорты внедрения (FR-4.6): CSV-парсер (RFC 4180) один на всех — его же
// использует admin BFF для импорта шаблонов и suppression-списка.

dependencies {
    api(project(":application"))

    implementation(libs.spring.boot.starter)
}
