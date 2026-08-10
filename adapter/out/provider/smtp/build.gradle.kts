// Email-канал (§9.3, EM-01…EM-03): пул SMTP-соединений, Message-ID Hub'а, DKIM,
// поллер bounce-ящика по IMAP (AD-06).

dependencies {
    api(project(":application"))
    implementation(project(":adapter:out:provider:support"))

    implementation(libs.spring.boot.starter)
    implementation(libs.jakarta.mail.api)
    runtimeOnly(libs.angus.mail)

    testImplementation(testFixtures(project(":adapter:out:provider:support")))
    testImplementation(libs.greenmail.junit5)
}
