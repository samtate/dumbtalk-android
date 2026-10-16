plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The Go bridge comes from scripts/build-wabridge.sh.
val bridge = file("libs/wabridge.aar")
if (!bridge.isFile) {
    throw GradleException("The WhatsApp bridge is missing: run scripts/build-wabridge.sh first")
}

android {
    namespace = "dev.dumbtalk.whatsapp"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(files(bridge))
}
