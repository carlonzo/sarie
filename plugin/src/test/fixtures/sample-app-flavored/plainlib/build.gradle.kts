plugins {
    id("com.android.library")
}

android {
    namespace = "sarie.sample.plainlib"
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
