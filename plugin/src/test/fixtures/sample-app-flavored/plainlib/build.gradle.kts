plugins {
    id("com.android.library")
}

android {
    namespace = "sarie.sample.plainlib"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}
