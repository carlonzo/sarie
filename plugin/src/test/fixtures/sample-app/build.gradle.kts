plugins {
    // Both plugins resolve from TestKit's injected classpath: AGP rides pluginUnderTestMetadata
    // (see plugin/build.gradle.kts) so plugin + AGP share one classloader.
    id("com.android.application")
    id("com.carlonzo.sarie")
}

android {
    namespace = "sarie.sample.fixture"
    compileSdk {
        val spec = libs.versions.compileSdk.get().split('.')
        version = release(spec[0].toInt()) {
            minorApiLevel = spec.getOrElse(1) { "0" }.toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}

dependencies {
    implementation(libs.okhttp)
}
