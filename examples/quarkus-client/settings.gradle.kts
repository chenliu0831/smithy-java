/**
 * Example showing the Typed-client programming model for the experimental
 * `quarkus-smithy` extension. The generated `CoffeeShopClient` is exposed as a
 * CDI bean and dials out to the upstream `examples/quarkus-server/` instance
 * over HTTP.
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

rootProject.name = "Quarkus-Client"
