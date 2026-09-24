plugins {
    alias(libs.plugins.android.application)
    id("com.carlonzo.sarie")
}

okhttpCronet {
    failOnUntested.set(true)
}

android {
    namespace = "sarie.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "sarie.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":bridge"))
    implementation(libs.okhttp)
    compileOnly(libs.cronet.api)
    implementation(libs.cronet.embedded)
    implementation(libs.material)
    implementation(libs.chucker)

    testImplementation(libs.junit)
}
