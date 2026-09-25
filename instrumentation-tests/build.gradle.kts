import java.net.Socket

plugins {
    alias(libs.plugins.android.application)
    id("com.carlonzo.sarie")
}

// This repo must not merge an unverified okhttp: Renovate bumps fail the pin instead of warning.
okhttpCronet {
    failOnUntested.set(true)
}

android {
    namespace = "sarie.instrumentation"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "sarie.instrumentation"
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // R8-minified variant the critical instrumented suite runs against (todo 10).
        // Keep rules live in proguard-rules.pro; each one names the exact linkage failure
        // it fixes. Never disable minification to make the suite pass.
        create("minifiedRelease") {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            // :bridge only has debug/release; consume its release variant here.
            matchingFallbacks += "release"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

// AGP creates the androidTest component only for `testBuildType` (default "debug"); force-
// enable it for minifiedRelease too so both connected*AndroidTest tasks coexist.
androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        if (variantBuilder.name == "minifiedRelease") {
            variantBuilder.androidTestEnabled = true
        }
    }
    onVariants(selector().all()) { variant ->
        // AGP's androidTest R8 task (keepAllForTest) inherits only dependency consumer
        // rules - NOT the build type's proguardFiles - so the -dontwarn rules for the
        // androidx.test errorprone annotations must be fed to it explicitly.
        variant.androidTest?.proguardFiles?.add(layout.projectDirectory.file("proguard-rules.pro"))
    }
}

val okhttpVersionForTests: String =
    providers.gradleProperty("okhttpVersion").orElse(libs.versions.okhttp).get()

dependencies {
    implementation(project(":bridge"))
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersionForTests")
    // Compile-only: main sources only build the engine; cronet-embedded supplies the
    // implementation (API + natives) on the device at instrumentation time.
    compileOnly(libs.cronet.api)
    // Referenced by TestAppRuntime to fetch a Context; only ever invoked from androidTest.
    compileOnly(libs.androidx.test.core)

    implementation(libs.cronet.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp.logging)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:$okhttpVersionForTests")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}

// The connected suite depends on host-side helpers (Caddy HTTP/3 origin + the /slow stall
// backend). These tasks make the Gradle task graph self-sufficient: no manual helper starts.
val startTestOrigin = tasks.register("startTestOrigin") {
    group = "verification"
    description = "Idempotently starts the Caddy HTTP/3 origin and the /slow stall backend."
    doLast {
        val bin = rootProject.file("scripts/bin")
        bin.mkdirs()

        fun caddyHealthy(): Boolean {
            val proc = ProcessBuilder("curl", "-sk", "--max-time", "5", "https://localhost:8443/ok")
                .start()
            val body = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            return proc.exitValue() == 0 && body == "ok"
        }
        if (!caddyHealthy()) {
            println("startTestOrigin: Caddy not healthy on :8443 - starting it")
            ProcessBuilder("./scripts/bin/caddy", "run", "--config", "scripts/Caddyfile")
                .directory(rootProject.projectDir)
                .redirectOutput(rootProject.file("scripts/bin/caddy.log"))
                .redirectErrorStream(true)
                .start()
            var up = false
            repeat(30) {
                if (!up) {
                    up = caddyHealthy()
                    if (!up) Thread.sleep(500)
                }
            }
            if (!up) {
                throw GradleException(
                    "Caddy did not become healthy on :8443 within 15s - see scripts/bin/caddy.log " +
                        "(certs present? run scripts/gen-certs.sh; port free?)",
                )
            }
        }

        // Local-origin QUIC from the emulator is blocked by Chromium's known-root policy,
        // so device traffic never writes HTTP/3 to the access log. Prove origin h3 from
        // the host instead (see CronetSuite KDoc and verifyH3ServerEvidence).
        val curlH3 = rootProject.file("scripts/bin/curl-http3")
        if (!curlH3.canExecute()) {
            val dl = ProcessBuilder("./scripts/download-curl-http3.sh")
                .directory(rootProject.projectDir)
                .inheritIO()
                .start()
            if (dl.waitFor() != 0) {
                throw GradleException("scripts/download-curl-http3.sh failed")
            }
        }
        val probe = ProcessBuilder(
            curlH3.absolutePath,
            "-sk",
            "--http3-only",
            "--max-time", "5",
            "https://127.0.0.1:8443/ok",
        ).directory(rootProject.projectDir).start()
        val probeBody = probe.inputStream.bufferedReader().readText()
        val probeErr = probe.errorStream.bufferedReader().readText()
        probe.waitFor()
        if (probe.exitValue() != 0 || probeBody != "ok") {
            throw GradleException(
                "host HTTP/3 probe of https://127.0.0.1:8443/ok failed " +
                    "(exit=${probe.exitValue()} body='$probeBody' err='$probeErr')",
            )
        }
        println("startTestOrigin: host HTTP/3 probe ok")

        // The /slow upstream port lives in scripts/Caddyfile (reverse_proxy 127.0.0.1:<port>).
        val slowPort = Regex("reverse_proxy\\s+127\\.0\\.0\\.1:(\\d+)")
            .find(rootProject.file("scripts/Caddyfile").readText())
            ?.groupValues?.get(1)?.toInt()
            ?: throw GradleException("could not parse the /slow reverse_proxy port from scripts/Caddyfile")

        fun slowUp(): Boolean = try {
            Socket("127.0.0.1", slowPort).close()
            true
        } catch (e: java.io.IOException) {
            false
        }
        if (!slowUp()) {
            println("startTestOrigin: slow-backend not listening on 127.0.0.1:$slowPort - starting it")
            val pidFile = rootProject.file("scripts/bin/slow-backend.pid")
            if (pidFile.exists()) {
                val stale = pidFile.readText().trim()
                if (stale.isNotEmpty()) {
                    ProcessBuilder("kill", stale).start().waitFor()
                }
                pidFile.delete()
            }
            val proc = ProcessBuilder("python3", "scripts/slow-backend.py")
                .directory(rootProject.projectDir)
                .redirectOutput(rootProject.file("scripts/bin/slow-backend.log"))
                .redirectErrorStream(true)
                .start()
            pidFile.writeText(proc.pid().toString())
            var up = false
            repeat(20) {
                if (!up) {
                    up = slowUp()
                    if (!up) Thread.sleep(250)
                }
            }
            if (!up) {
                throw GradleException(
                    "slow-backend did not listen on 127.0.0.1:$slowPort within 5s - " +
                        "see scripts/bin/slow-backend.log",
                )
            }
        }
        println("startTestOrigin: Caddy (:8443 h3) + slow-backend (127.0.0.1:$slowPort) ready")
    }
}

// Stops only the stall backend via its PID file; Caddy stays running (harmless daemon, and
// verifyH3ServerEvidence reads its access log afterwards).
val stopTestOrigin = tasks.register("stopTestOrigin") {
    group = "verification"
    description = "Stops the /slow stall backend via its PID file (Caddy is left running)."
    doLast {
        val pidFile = rootProject.file("scripts/bin/slow-backend.pid")
        if (pidFile.exists()) {
            val pid = pidFile.readText().trim()
            if (pid.isNotEmpty()) {
                ProcessBuilder("kill", pid).start().waitFor()
            }
            pidFile.delete()
            println("stopTestOrigin: slow-backend (pid $pid) stopped; Caddy left running")
        }
    }
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

tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    dependsOn(startTestOrigin)
    finalizedBy(verifyH3ServerEvidence, stopTestOrigin)
}
