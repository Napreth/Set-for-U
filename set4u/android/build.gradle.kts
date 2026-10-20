plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.napreth.set4u"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.napreth.set4u"
        minSdk = 23
        targetSdk = 37
        versionCode = providers.gradleProperty("set4u.versionCode").get().toInt()
        versionName = providers.gradleProperty("set4u.version").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = null
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { aidl = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    implementation(libs.zxing.core)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.compose.backhandler)
    implementation(libs.activity.compose)
    implementation(libs.miuix)
    implementation(libs.coui)
    implementation(libs.cupertino)
    implementation(libs.backdrop)
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
android.sourceSets.getByName("androidTest").kotlin.directories.add("../core/src/bridgeTest/kotlin")
android.sourceSets.getByName("main").kotlin.directories.add("../ui/src/main/kotlin")

val syncAppLicense by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("assets/licenses")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/app-assets"))
}
android.sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/app-assets").get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(syncAppLicense) }
