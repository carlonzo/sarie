plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.2.20"
}

gradlePlugin {
    plugins {
        create("transport") {
            id = "dev.okhttpcronet.transport"
            implementationClass = "dev.okhttpcronet.plugin.TransportPlugin"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.13.0")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
    // Test-only: rewriter and fingerprint work compile against OkHttp classes; never exposed as api.
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
