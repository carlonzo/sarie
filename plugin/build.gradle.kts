plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

gradlePlugin {
    plugins {
        create("sarie") {
            id = "com.carlonzo.sarie"
            implementationClass = "sarie.plugin.TransportPlugin"
            displayName = "Sarie"
            description = "HTTP/3 for OkHttp apps via Cronet"
        }
    }
}

dependencies {
    compileOnly(libs.agp.api)
    compileOnly(libs.asm)
    compileOnly(libs.asm.commons)
    // Test-only: rewriter and fingerprint work compile against OkHttp classes; never exposed as api.
    compileOnly(libs.okhttp.min)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
    // Needed by the visitor unit tests: ClassData fake requires the AGP API on the test classpath
    // (compileOnly does not reach tests).
    testImplementation(libs.agp.api)
    testImplementation(libs.asm)
    testImplementation(libs.okhttp.min)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// TestKit's injected plugin classloader is isolated from the fixture's own plugin loaders, so the
// fixture cannot link against gradle-api or apply AGP next to the plugin-under-test unless AGP
// rides the SAME injected classpath (same trick as square/wire and square/leakcanary).
val agpForTests = configurations.create("agpForTests")
dependencies {
    agpForTests(libs.agp)
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
