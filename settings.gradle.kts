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
// (провайдеры — под adapter/out/provider/), observability — ни то ни другое. Пути проектов
// повторяют каталоги (:adapter:in:rest = adapter/in/rest), поэтому projectDir не задаётся.
// Сам :adapter — агрегатор: он собирает все адаптеры разом (./gradlew :adapter:build) и является
// единственной зависимостью bootstrap'а, которому по определению нужны все (композиционный корень).
// Пакеты Java при разделении не менялись: границы слоёв проверяет ArchUnit, границы адаптеров —
// теперь ещё и Gradle.
include("adapter")

listOf("admin", "callback", "contract", "importer", "kafka", "rest", "scheduler", "security")
    .forEach { name -> include("adapter:in:$name") }

listOf("compliance", "kafka", "metrics", "persistence", "policy", "time")
    .forEach { name -> include("adapter:out:$name") }

listOf("apns", "fcm", "mock", "playmobile", "smsgate", "smtp", "support")
    .forEach { name -> include("adapter:out:provider:$name") }

include("adapter:observability")
