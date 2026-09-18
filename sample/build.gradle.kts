plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("dev.okhttpcronet.transport")
}

android {
    namespace = "dev.okhttpcronet.sample"
    // Minor-release platform dir on disk is android-37.0; compileSdk = 37 looks up "android-37" and misses it.
    compileSdkVersion = "android-37.0"

    defaultConfig {
        applicationId = "dev.okhttpcronet.sample"
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":bridge"))
    implementation(libs.okhttp)
    // Compile-only: main sources only build the engine; cronet-embedded supplies the
    // implementation (API + natives) on the device at instrumentation time.
    compileOnly(libs.cronet.api)
    // Referenced by SampleAppRuntime to fetch a Context; only ever invoked from androidTest.
    compileOnly(libs.androidx.test.core)

    implementation(libs.cronet.embedded)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
