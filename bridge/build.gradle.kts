plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.okhttpcronet.bridge"
    // Minor-release platform dir on disk is android-37.0; compileSdk = 37 looks up "android-37" and misses it.
    compileSdkVersion = "android-37.0"

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // JVM tests touch android.jar stubs; return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    compileOnly(libs.cronet.api)
    testImplementation(libs.cronet.api)
    testImplementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
}
