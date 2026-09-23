plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.bcv)
}

android {
    namespace = "sarie.bridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // JVM tests touch android.jar stubs; return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }

    lint {
        // Experimental Cronet options (ConnectionMigrationOptions, DnsOptions) used internally.
        disable.add("UnsafeOptInUsageError")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 5.5.0 marks internals with @OkHttpInternalApi; 5.4.0 does not ship that annotation.
// Main compiles against 5.4.0 (Suppress is enough). Tests compile against the matrix
// version, so opt-in via compiler flag — missing annotation is ignored on 5.4.0.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        compilerOptions.optIn.add("okhttp3.internal.OkHttpInternalApi")
        compilerOptions.optIn.add("sarie.bridge.SarieInternalApi")
    }
}

// Host apps own OkHttp. compileOnly against the oldest supported version so we cannot
// accidentally use newer APIs; Gradle will not pull a version into the consumer graph.
val okhttpVersionForTests: String =
    providers.gradleProperty("okhttpVersion").orElse(libs.versions.okhttp).get()

dependencies {
    compileOnly(libs.okhttp.min)
    compileOnly(libs.cronet.api)
    compileOnly(libs.play.services.cronet)
    testImplementation(libs.cronet.api)
    testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersionForTests")
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver3:$okhttpVersionForTests")
}

// compileOnly must not leak okhttp or cronet onto the published/runtime classpath.
tasks.register("checkOkHttpCompileOnly") {
    group = "verification"
    description = "Fails if okhttp is resolved on the bridge runtime classpath."
    doLast {
        val found = configurations.getByName("debugRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "com.squareup.okhttp3" && it.name.startsWith("okhttp") }
        check(found.isEmpty()) {
            "bridge must not ship okhttp on its runtime classpath (compileOnly against " +
                "${libs.versions.okhttpMin.get()}); found $found"
        }
    }
}
tasks.register("checkCronetCompileOnly") {
    group = "verification"
    description = "Fails if cronet is resolved on the bridge runtime classpath."
    doLast {
        val found = configurations.getByName("debugRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "org.chromium.net" }
        check(found.isEmpty()) {
            "bridge must not ship cronet on its runtime classpath (compileOnly against " +
                "${libs.versions.cronetApi.get()}); found $found"
        }
    }
}
tasks.register("checkPlayServicesCompileOnly") {
    group = "verification"
    description = "Fails if play-services-cronet is resolved on the bridge runtime classpath."
    doLast {
        val found = configurations.getByName("debugRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "com.google.android.gms" }
        check(found.isEmpty()) {
            "bridge must not ship play-services on its runtime classpath; found $found"
        }
    }
}
tasks.named("check") {
    dependsOn("checkOkHttpCompileOnly", "checkCronetCompileOnly", "checkPlayServicesCompileOnly")
}

dependencies {
    "bcv-rt-jvm-cp"("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
}

val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
    group = "verification"
    description = "Builds the public API dump for the bridge module."
    inputClassesDirs.from(
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileReleaseKotlin")
            .flatMap { it.destinationDirectory }
    )
    outputApiFile.set(layout.buildDirectory.file("api/bridge.api"))
    nonPublicMarkers.add("sarie.bridge.SarieInternalApi")
}

val apiCheck = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
    group = "verification"
    description = "Checks that the public API dump matches the project API declaration."
    projectApiFile.set(layout.projectDirectory.file("api/bridge.api"))
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}

tasks.register<Copy>("apiDump") {
    group = "verification"
    description = "Updates the public API dump file in api/bridge.api."
    from(apiBuild.flatMap { it.outputApiFile })
    into(layout.projectDirectory.dir("api"))
    rename { "bridge.api" }
}

tasks.named("check") {
    dependsOn(apiCheck)
}
