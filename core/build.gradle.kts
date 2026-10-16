plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.dumbtalk.core"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Android's org.json is stubbed on the JVM; the real one for tests.
    testImplementation(libs.orgjson)
    testImplementation(libs.kotlinx.coroutines.test)
}
