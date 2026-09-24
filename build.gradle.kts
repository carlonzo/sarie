plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.bcv) apply false
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes :bridge and the included :plugin build to the local Maven repo."
    dependsOn(":bridge:publishToMavenLocal")
    dependsOn(gradle.includedBuild("plugin").task(":publishToMavenLocal"))
}

tasks.register("publishToMavenCentral") {
    group = "publishing"
    description = "Uploads :bridge and the included :plugin build to Maven Central (no auto-release)."
    dependsOn(":bridge:publishToMavenCentral")
    dependsOn(gradle.includedBuild("plugin").task(":publishToMavenCentral"))
}
