plugins {
    // Автопровижининг JDK для toolchain (Java 25), если он не установлен локально.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "notification-hub"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// AR-01: гексагональные слои — отдельные Gradle-модули.
// Направление зависимостей: adapter -> application -> domain, bootstrap -> все.
include("domain")
include("application")
include("bootstrap")

// AR-04: каждый адаптер — отдельный модуль; driving под adapter/in/, driven под adapter/out/
// (провайдеры — под adapter/out/provider/), observability — ни то ни другое. Имена плоские
// (:adapter-in-*, :adapter-out-*), чтобы не заводить пустые промежуточные проекты. Пакеты
// остались прежними — границы слоёв по-прежнему проверяет ArchUnit, границы адаптеров
// теперь держит и Gradle.
fun adapterModule(name: String, dir: String) {
    include("adapter-$name")
    project(":adapter-$name").projectDir = file("adapter/$dir")
}

listOf("admin", "callback", "contract", "importer", "kafka", "rest", "scheduler", "security")
    .forEach { name -> adapterModule("in-$name", "in/$name") }

listOf("compliance", "kafka", "metrics", "persistence", "policy", "secret", "time")
    .forEach { name -> adapterModule("out-$name", "out/$name") }

listOf("apns", "fcm", "playmobile", "smsgate", "smtp", "support")
    .forEach { name -> adapterModule("out-provider-$name", "out/provider/$name") }

adapterModule("observability", "observability")
