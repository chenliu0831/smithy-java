/**
 * Example showing the Types-only programming model for the experimental
 * `quarkus-smithy` extension. Smithy is the source of truth for data classes;
 * the Quarkus app dispatches them itself via Vert.x routes using Smithy's
 * own JSON codec.
 */

pluginManagement {
    val quarkusPluginVersion: String by settings

    plugins {
        id("io.quarkus").version(quarkusPluginVersion)
    }

    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Quarkus-Types"
