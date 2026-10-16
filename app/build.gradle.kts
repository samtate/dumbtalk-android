import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Telegram app credentials (my.telegram.org): TELEGRAM_API_ID/TELEGRAM_API_HASH
// from the environment (CI secrets) or telegram.api_id/telegram.api_hash in
// local.properties. Without them, setup asks on the phone.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val telegramApiId = System.getenv("TELEGRAM_API_ID") ?: localProperties.getProperty("telegram.api_id") ?: "0"
val telegramApiHash = System.getenv("TELEGRAM_API_HASH") ?: localProperties.getProperty("telegram.api_hash") ?: ""

android {
    namespace = "dev.dumbtalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dumbtalk.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.ifBlank { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    signingConfigs {
        // Committed on purpose: every CI debug build shares one signature so
        // a newer debug APK installs over an older one. Not a secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystore = System.getenv("DUMBTALK_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("DUMBTALK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DUMBTALK_KEY_ALIAS")
                keyPassword = System.getenv("DUMBTALK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (System.getenv("DUMBTALK_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":svc-signal"))
    implementation(project(":svc-telegram"))
    implementation(project(":svc-whatsapp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
