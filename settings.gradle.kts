pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Set-for-U"

include(":core", ":android", ":desktop")
project(":core").projectDir = file("set4u/core")
project(":android").projectDir = file("set4u/android")
project(":desktop").projectDir = file("set4u/desktop")

include(":testTarget")
project(":testTarget").projectDir = file("test-fixtures/target")
