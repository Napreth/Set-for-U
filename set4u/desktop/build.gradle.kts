import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    application
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
kotlin.sourceSets.named("main") { kotlin.srcDir("../ui/src/main/kotlin") }
application { mainClass.set("com.napreth.set4u.desktop.MainKt") }
tasks.jar { manifest.attributes["Implementation-Version"] = project.version.toString() }
tasks.processResources {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("assets/licenses")) { into("licenses") }
}

tasks.register<Sync>("stagePortable") {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir(providers.gradleProperty("set4u.buildType").map { "portable-input/$it" }))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}
dependencies {
    implementation(compose.desktop.common)
    implementation(compose.material3)
    implementation(libs.compose.backhandler)
    implementation(libs.miuix)
    implementation(libs.coui)
    implementation(libs.cupertino)
    implementation(libs.backdrop)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    runtimeOnly(compose.desktop.windows_x64)
    runtimeOnly(compose.desktop.linux_x64)
    api(project(":core"))
    testImplementation(libs.junit)
}
tasks.test {
    val serial = providers.environmentVariable("SET4U_TEST_ADB_SERIAL").getOrElse("")
    environment("SET4U_TEST_ADB_SERIAL", serial)
    inputs.property("adbSerial", serial)
    val rootTests = providers.environmentVariable("SET4U_TEST_ROOT").getOrElse("0")
    environment("SET4U_TEST_ROOT", rootTests)
    inputs.property("rootTests", rootTests)
    val targetPackage = providers.environmentVariable("SET4U_TEST_TARGET_PACKAGE").getOrElse("com.napreth.doihave")
    environment("SET4U_TEST_TARGET_PACKAGE", targetPackage)
    inputs.property("testTargetPackage", targetPackage)
    val deviceTests = serial.isNotEmpty()
    outputs.upToDateWhen { !deviceTests }
}
kotlin.sourceSets.named("test") { kotlin.srcDir("../core/src/bridgeTest/kotlin") }
