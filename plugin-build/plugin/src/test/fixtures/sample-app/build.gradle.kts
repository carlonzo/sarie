plugins {
    // Both plugins resolve from TestKit's injected classpath: AGP rides pluginUnderTestMetadata
    // (see plugin/build.gradle.kts) so plugin + AGP share one classloader.
    id("com.android.application")
    id("dev.okhttpcronet.transport")
}

android {
    namespace = "dev.okhttpcronet.sample.fixture"
    // Minor-release platform dir on disk is android-37.0; compileSdk = 37 looks up "android-37" and misses it.
    compileSdkVersion = "android-37.0"

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
