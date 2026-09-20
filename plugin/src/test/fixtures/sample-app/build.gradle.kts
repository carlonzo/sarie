plugins {
    // Both plugins resolve from TestKit's injected classpath: AGP rides pluginUnderTestMetadata
    // (see plugin/build.gradle.kts) so plugin + AGP share one classloader.
    id("com.android.application")
    id("com.carlonzo.sarie")
}

android {
    namespace = "dev.okhttpcronet.sample.fixture"
    // Minor-release platform dir on disk is android-37.0; CI passes -Pokhttpcronet.compileSdk=android-37.
    compileSdkVersion = providers.gradleProperty("okhttpcronet.compileSdk").orElse("android-37.0").get()

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
