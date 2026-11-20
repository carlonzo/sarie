plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}

android {
    namespace = "sarie.sample.flavored"
    compileSdk {
        val spec = libs.versions.compileSdk.get().split('.')
        version = release(spec[0].toInt()) {
            minorApiLevel = spec.getOrElse(1) { "0" }.toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    flavorDimensions += listOf("target")
    productFlavors {
        create("dev") { dimension = "target" }
    }
}

dependencies {
    // Plain (flavor-less) project dependency: its variants lack the `target` flavor attribute,
    // so a raw file resolution of the app's runtime classpath is variant-ambiguous.
    implementation(project(":plainlib"))
    implementation(libs.okhttp)
}
