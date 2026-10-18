plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.napreth.set4u.testtarget"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.napreth.set4u.testtarget"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
}
