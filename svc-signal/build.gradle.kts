plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The native library, its Kotlin bindings and the TLS verifier AAR come from
// scripts/build-sigbridge.sh.
val bindings = file("src/main/kotlin-generated")
if (!bindings.isDirectory) {
    throw GradleException("The Signal bridge is missing: run scripts/build-sigbridge.sh first")
}

android {
    namespace = "dev.dumbtalk.signal"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir(bindings)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(files("libs/rustls-platform-verifier.aar"))
}
