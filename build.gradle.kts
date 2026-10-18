buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:9.1.56") }
}

plugins {
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val baseVersion = providers.gradleProperty("set4u.version").get()
val buildType = providers.gradleProperty("set4u.buildType").get()
require(buildType in setOf("debug", "release")) { "set4u.buildType must be debug or release" }
allprojects { version = baseVersion + if (buildType == "debug") "-debug" else "" }
