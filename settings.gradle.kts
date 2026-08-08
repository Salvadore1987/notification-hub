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
include("adapter")
include("bootstrap")
