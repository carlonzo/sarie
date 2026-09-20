plugins {
    // Both plugins resolve from TestKit's injected classpath: AGP rides pluginUnderTestMetadata
    // (see plugin/build.gradle.kts) so plugin + AGP share one classloader.
    id("com.android.application")
    id("com.carlonzo.sarie")
}

android {
    namespace = "dev.okhttpcronet.sample.fixture"
    // API 37 ships as platforms;android-37.0. CI passes the same via ANDROID_COMPILE_SDK.
    compileSdkVersion = providers.gradleProperty("okhttpcronet.compileSdk").orElse("android-37.0").get()

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
