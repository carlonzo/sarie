plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.okhttpcronet.sample"
    // Minor-release platform dir on disk is android-37.0; compileSdk = 37 looks up "android-37" and misses it.
    compileSdkVersion = "android-37.0"

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
