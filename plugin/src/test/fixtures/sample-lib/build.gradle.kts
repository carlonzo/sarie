plugins {
    // Both plugins resolve from TestKit's injected classpath: AGP rides pluginUnderTestMetadata
    // (see plugin/build.gradle.kts) so plugin + AGP share one classloader.
    id("com.android.library")
    id("com.carlonzo.sarie")
}

android {
    namespace = "dev.okhttpcronet.sample.fixture.lib"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
