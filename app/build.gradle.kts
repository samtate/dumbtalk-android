plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Telegram credentials are entered on the device and never compiled into an APK.

// Release scripts and CI use a version plus an epoch-seconds versionCode, so
// switching between local and CI releases keeps updates increasing. Manual
// development builds default to 1 / "dev".
val buildNumber = System.getenv("DUMBTALK_BUILD_NUMBER")?.toIntOrNull() ?: 1
val buildVersion = System.getenv("DUMBTALK_VERSION")?.takeIf { it.isNotBlank() } ?: "dev"

android {
    namespace = "dev.dumbtalk.app"
    compileSdk = 35

    defaultConfig {
        buildConfigField("boolean", "DEMO", "false")
        applicationId = "dev.dumbtalk.app"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = buildVersion
        ndk { abiFilters += "arm64-v8a" }
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
        // A copy that installs beside the real app as <package>.demo, starts with
        // made-up data and leaves the rest of the phone alone.
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "DEMO", "true")
            matchingFallbacks += listOf("debug")
        }
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
