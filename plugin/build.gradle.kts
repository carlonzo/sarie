plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

gradlePlugin {
    plugins {
        create("sarie") {
            id = "com.carlonzo.sarie"
            implementationClass = "dev.okhttpcronet.plugin.TransportPlugin"
            displayName = "Sarie"
            description = "HTTP/3 for OkHttp apps via Cronet"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.13.0")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
    // Test-only: rewriter and fingerprint work compile against OkHttp classes; never exposed as api.
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    // Needed by the visitor unit tests: ClassData fake requires the AGP API on the test classpath
    // (compileOnly does not reach tests).
    testImplementation("com.android.tools.build:gradle-api:8.13.0")
    testImplementation("org.ow2.asm:asm:9.7.1")
    testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// TestKit's injected plugin classloader is isolated from the fixture's own plugin loaders, so the
// fixture cannot link against gradle-api or apply AGP next to the plugin-under-test unless AGP
// rides the SAME injected classpath (same trick as square/wire and square/leakcanary).
val agpForTests by configurations.creating
dependencies {
    agpForTests("com.android.tools.build:gradle:8.13.0")
}
tasks.withType<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>().configureEach {
    pluginClasspath.from(agpForTests)
}

// Evidence runs tee the TestKit build output (guards, pin failure) into the console.
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
