plugins {
    `java-library`
    // Version pinned here so this example builds either standalone (gradle from
    // this directory) or as a subproject of smithy-java's root build.
    id("io.quarkus") version "3.35.3"
}

repositories {
    mavenLocal()
    mavenCentral()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project
val smithyJavaVersion: String by project

dependencies {
    // The experimental quarkus-smithy extension. Brings codegen via the
    // CodeGenProvider; the SmithyServerLifecycle bean is harmless in the
    // Typed-client model — Instance<Server> resolves to nothing and the
    // startup/shutdown observers no-op.
    implementation("software.amazon.smithy.java:quarkus-smithy:$smithyJavaVersion")

    // Quarkus runtime + REST/Vert.x for the proxy resource.
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-vertx-http")

    // Smithy-Java client runtime + restJson1 protocol so the generated
    // CoffeeShopClient can dial out over HTTP.
    implementation("software.amazon.smithy.java:client-core:$smithyJavaVersion")
    implementation("software.amazon.smithy.java:aws-client-restjson:$smithyJavaVersion")

    // The proxy resource serializes/deserializes shapes on its own HTTP
    // boundary using Smithy's JSON codec.
    implementation("software.amazon.smithy.java:json-codec:$smithyJavaVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Quarkus's bootstrap resolves dependencies via Maven (mavenLocal,
// mavenCentral, …), not via Gradle's project-substitution graph. So before this
// example can build, every project artifact it depends on must be in
// mavenLocal.
val publishExtension = listOf(
    ":quarkus-smithy:publishToMavenLocal",
    ":quarkus-smithy-deployment:publishToMavenLocal",
    ":codecs:json-codec:publishToMavenLocal",
)
tasks.matching { it.name in setOf("quarkusGenerateAppModel", "quarkusBuild", "quarkusDev", "quarkusRun") }
    .configureEach {
        publishExtension.forEach { dependsOn(it) }
    }

// .smithy files live under src/main/smithy/ — Quarkus's CodeGenProvider
// finds them automatically and IntelliJ's Gradle import surfaces them
// without extra source-set wiring.
