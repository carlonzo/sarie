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
    compileOnly(libs.asm.tree)
    // Test-only: rewriter and fingerprint work compile against OkHttp classes; never exposed as api.
    compileOnly(libs.okhttp.min)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
    // Needed by the visitor unit tests: ClassData fake requires the AGP API on the test classpath
    // (compileOnly does not reach tests).
    testImplementation(libs.agp.api)
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
    testImplementation(libs.okhttp.min)
}

// Stock OkHttp bytecode for the rewriter tests is read straight out of these jars at test time
// instead of being checked in under src/test/resources/stock/.
//
// The set of jars is derived from RecipeRegistry, NOT from the version catalog. The tests
// parameterize over `RecipeRegistry.recipes`, so taking the versions from the catalog instead
// would make every one of them fail the next time a pin is added until this file was edited too.
val recipeVersions: List<String> =
    file("src/main/kotlin/sarie/plugin/RecipeRegistry.kt")
        .readText()
        .let { source ->
            Regex("\"(\\d+\\.\\d+\\.\\d+)\"\\s+to\\s+recipe\\(")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
        }

require(recipeVersions.isNotEmpty()) {
    "no recipe versions parsed from RecipeRegistry.kt; the stock-OkHttp test wiring would be empty"
}

// One configuration per version: a single module coordinate resolves to exactly one version, so
// they cannot share a resolvable configuration. Non-transitive, because only the okhttp artifact
// itself is wanted - otherwise the test would be handed one colon-separated path for the whole
// transitive closure.
val stockOkhttp: Map<String, Configuration> = recipeVersions.associateWith { version ->
    configurations.create("stockOkhttp${version.replace(".", "")}") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
}

dependencies {
    recipeVersions.forEach { version ->
        add("stockOkhttp${version.replace(".", "")}", "com.squareup.okhttp3:okhttp:$version")
    }
}

// Resolved at execution time rather than during configuration.
tasks.withType<Test>().configureEach {
    val jars = stockOkhttp
    doFirst {
        jars.forEach { (version, configuration) ->
            systemProperty("sarie.stock.okhttp.$version", configuration.singleFile.absolutePath)
        }
    }
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
