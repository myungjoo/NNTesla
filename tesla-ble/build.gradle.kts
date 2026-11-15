// Android BLE transport for the vehicle-command protocol, plus the latency-first polling
// scheduler that drives the fast (speed) and slow paths.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.myungjoo.nntesla.ble"
    compileSdk = 34

    defaultConfig {
        // 31: Android Keystore ECDH (PURPOSE_AGREE_KEY) keeps the vehicle key in hardware.
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":tesla-protocol"))
    api(project(":vehicle-data"))
    implementation(libs.kotlinx.coroutines.android)
}
