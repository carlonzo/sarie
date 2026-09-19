plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("dev.okhttpcronet.transport")
}

android {
    namespace = "dev.okhttpcronet.sample"
    // Minor-release platform dir on disk is android-37.0; compileSdk = 37 looks up "android-37" and misses it.
    compileSdkVersion = "android-37.0"

    defaultConfig {
        applicationId = "dev.okhttpcronet.sample"
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Compile-only: main sources only build the engine; cronet-embedded supplies the
    // implementation (API + natives) on the device at instrumentation time.
    compileOnly(libs.cronet.api)
    // Referenced by SampleAppRuntime to fetch a Context; only ever invoked from androidTest.
    compileOnly(libs.androidx.test.core)

    implementation(libs.cronet.embedded)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}

// Server-side half of the HTTP/3 proof: the Caddy origin (started per scripts/Caddyfile
// header) writes a JSON access log on the build machine; HTTP/3 requests log
// request.proto as "HTTP/3.0". This task fails unless at least one HTTP/3 entry exists,
// so the h3 test cannot pass on client-side protocol alone.
val verifyH3ServerEvidence = tasks.register("verifyH3ServerEvidence") {
    group = "verification"
    description = "Fails unless the Caddy access log contains HTTP/3 entries (server-side h3 evidence)."
    doLast {
        val logFile = rootProject.file("scripts/bin/caddy-access.log")
        if (!logFile.exists()) {
            throw GradleException(
                "Caddy access log missing at $logFile - start the origin per scripts/Caddyfile header " +
                    "(python3 scripts/slow-backend.py & ; ./scripts/bin/caddy run --config scripts/Caddyfile &)",
            )
        }
        val h3Entries = logFile.readLines().count { it.contains("\"proto\":\"HTTP/3") }
        if (h3Entries == 0) {
            throw GradleException(
                "No HTTP/3 entries in $logFile - the device suite never negotiated HTTP/3 " +
                    "against the origin (check UDP reachability emulator->host, quic hint, certs).",
            )
        }
        println("verifyH3ServerEvidence: $h3Entries HTTP/3 entries in $logFile")
    }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    finalizedBy(verifyH3ServerEvidence)
}
