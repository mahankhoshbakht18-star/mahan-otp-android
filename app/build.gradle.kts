// Module-level build.gradle.kts
// مسیر: app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Private credentials are injected at build time, never committed to Git.
val connectionFile = providers.environmentVariable("MAHAN_CONNECTION_FILE")
    .orElse(rootProject.layout.projectDirectory.file(".private/connection.json").asFile.absolutePath)
val generatedConnectionAssets = layout.buildDirectory.dir("generated/connectionAssets")
val prepareConnectionAssets by tasks.registering {
    inputs.files(providers.provider { listOf(file(connectionFile.get())).filter { it.exists() } })
    outputs.dir(generatedConnectionAssets)
    doLast {
        val output = generatedConnectionAssets.get().file("mahan-connection.json").asFile
        output.parentFile.mkdirs()
        val source = file(connectionFile.get())
        if (source.exists()) source.copyTo(output, overwrite = true)
        else if (output.exists()) output.delete()
    }
}
tasks.named("preBuild") { dependsOn(prepareConnectionAssets) }

android {
    namespace = "com.example.codresan"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.mahanvip.otp"
        minSdk = 24     // پشتیبانی از اندروید ۷ به بالا
        targetSdk = 36
        versionCode = 5
        versionName = "2.2.0"

        fun publicValue(name: String, fallback: String = "") = "\"" + (System.getenv(name) ?: fallback)
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""
        buildConfigField("String", "SUPPORT_TELEGRAM", publicValue("MAHAN_SUPPORT_TELEGRAM", "cofenetonline25802"))
        buildConfigField("String", "SUPPORT_PHONE", publicValue("MAHAN_SUPPORT_PHONE", "09154372582"))
        buildConfigField("String", "SUPPORT_HOURS", publicValue("MAHAN_SUPPORT_HOURS", "همه‌روزه ۹:۰۰ الی ۲۱:۰۰"))
        buildConfigField("String", "SUPPORT_NAME", publicValue("MAHAN_SUPPORT_NAME", "امیر نرانی"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("production") {
            val keyPath = System.getenv("MAHAN_KEYSTORE")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = System.getenv("MAHAN_STORE_PASSWORD")
                keyAlias = System.getenv("MAHAN_KEY_ALIAS")
                keyPassword = System.getenv("MAHAN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // CI builds unsigned; the original key never leaves the owner's machine.
            signingConfig = signingConfigs.getByName("production")
                .takeIf { !System.getenv("MAHAN_KEYSTORE").isNullOrBlank() }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // فعال کردن ViewBinding برای دسترسی راحت به دکمه‌ها و فیلدهای XML
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(generatedConnectionAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // کتابخانه‌های استاندارد اندروید (از فایل libs.versions.toml خوانده می‌شوند)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)

    // --- کتابخانه‌های اختصاصی برای ربات مِهان ---
    // برای طراحی کارت‌های وضعیت (Status Cards)
    implementation("androidx.cardview:cardview:1.0.0")

    // برای ارسال پیامک‌ها به سرور کلودفلر (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // برای مشاهده لاگ‌های شبکه (دیباگ کردن)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // تست‌های برنامه
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
