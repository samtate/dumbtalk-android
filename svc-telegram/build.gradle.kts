plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// TDLib's Java bindings and native library come from scripts/fetch-tdlib.sh.
val tdlib = file("tdlib")
if (!tdlib.resolve("java").isDirectory) {
    throw GradleException("TDLib is missing: run scripts/fetch-tdlib.sh first")
}

android {
    namespace = "dev.dumbtalk.telegram"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir(tdlib.resolve("java"))
    sourceSets["main"].jniLibs.srcDir(tdlib.resolve("libs"))
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    // TDLib's generated TdApi.java uses @IntDef.
    implementation(libs.androidx.annotation)
}
