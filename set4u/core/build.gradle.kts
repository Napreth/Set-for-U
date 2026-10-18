import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val sdkRoot = providers.environmentVariable("ANDROID_HOME").orNull ?: run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(local::load)
    requireNotNull(local.getProperty("sdk.dir")) { "Set ANDROID_HOME or sdk.dir in local.properties" }
}
val androidPlatform = listOf("android-37", "android-37.0").map { file("$sdkRoot/platforms/$it/android.jar") }
    .firstOrNull { it.isFile } ?: error("Install Android SDK platform 37")
dependencies { compileOnly(files(androidPlatform)) }

// DeviceTask DEX compilation must understand the Kotlin metadata emitted by this build.
val deviceDexCompiler by configurations.creating { isCanBeConsumed = false; isTransitive = false }
dependencies { deviceDexCompiler("com.android.tools:r8:9.1.56") }
val deviceBundle = tasks.register<com.napreth.set4u.build.DeviceBundle>("buildDeviceTasks") {
    dependsOn(tasks.compileKotlin, tasks.compileJava)
    classDirectories.from(sourceSets.main.get().output.classesDirs)
    libraries.from(configurations.runtimeClasspath)
    androidJar.set(androidPlatform)
    d8Jar.set(layout.file(deviceDexCompiler.elements.map { it.single().asFile }))
    buildType.set(providers.gradleProperty("set4u.buildType"))
    buildVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir(providers.gradleProperty("set4u.buildType").map { "device-tasks/$it" }))
}
val uiMetadataDirectory = layout.buildDirectory.dir(providers.gradleProperty("set4u.buildType").map { "ui-metadata/$it" })
val uiMetadata = tasks.register<JavaExec>("exportUiMetadata") {
    dependsOn(tasks.compileKotlin, tasks.compileJava)
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("com.napreth.set4u.core.metadata.UiMetadataExporter")
    val destination = uiMetadataDirectory
    args(destination.get().asFile.absolutePath, project.version.toString())
    outputs.dir(destination)
    inputs.property("metadataVersion", project.version.toString())
}

tasks.jar {
    dependsOn(deviceBundle)
    dependsOn(uiMetadata)
    from(uiMetadataDirectory)
    val exclusions = deviceBundle.flatMap { it.outputDirectory.file("host-excludes.txt") }
    inputs.file(exclusions)
    exclude(com.napreth.set4u.build.DeviceClassFilter(exclusions))
    from(deviceBundle.map { it.outputDirectory.dir("resources") })
}
// Consumers compile against the filtered JAR, not the unfiltered compiler output directory.
configurations.named("apiElements") { outgoing.variants.removeIf { it.name == "classes" } }
configurations.named("runtimeElements") { outgoing.variants.removeIf { it.name == "classes" } }
tasks.test {
    dependsOn(deviceBundle)
    dependsOn(uiMetadata)
    classpath += files(deviceBundle.map { it.outputDirectory.dir("resources") })
    classpath += files(layout.buildDirectory.dir(providers.gradleProperty("set4u.buildType").map { "ui-metadata/$it" }))
}
