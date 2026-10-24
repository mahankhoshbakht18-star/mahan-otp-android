plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10") }
android {
    namespace = "com.example.codresan.qa"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.codresan.qa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("probe") {
            val path = System.getenv("MAHAN_KEYSTORE")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("MAHAN_STORE_PASSWORD")
                keyAlias = System.getenv("MAHAN_KEY_ALIAS")
                keyPassword = System.getenv("MAHAN_KEY_PASSWORD")
            }
        }
    }
    buildTypes.getByName("release").signingConfig = signingConfigs.getByName("probe")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
