plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}

android {
    namespace = "sarie.sample.flavored"
    compileSdk = libs.versions.compileSdk.get().toInt()

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
