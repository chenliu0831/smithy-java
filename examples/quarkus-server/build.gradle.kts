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
    // The experimental quarkus-smithy extension. Brings in:
    //   - the runtime SmithyServerLifecycle bean
    //   - the deployment-time CodeGenProvider that runs Smithy code generation
    //     during quarkusGenerateCode (no smithy-base Gradle plugin needed)
    implementation("software.amazon.smithy.java:quarkus-smithy:$smithyJavaVersion")

    // Quarkus runtime
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-arc")

    // Quarkus's Vert.x HTTP server. The Smithy server (Netty) listens on its
    // own port (8888), so this Vert.x server does not handle Smithy traffic —
    // it exists to host the Quarkus Dev UI at /q/dev in dev mode and
    // management endpoints (/q/health, /q/metrics, …) when those extensions
    // are added.
    implementation("io.quarkus:quarkus-vertx-http")

    // Smithy-Java server runtime (Netty transport + restJson1 protocol).
    implementation("software.amazon.smithy.java:server-netty:$smithyJavaVersion")
    implementation("software.amazon.smithy.java:aws-server-restjson:$smithyJavaVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Quarkus's bootstrap resolves the deployment artifact via Maven (mavenLocal,
// mavenCentral, …), not via Gradle's project-substitution graph. So before this
// example can build, the extension's deployment jar must be in mavenLocal.
// Wire the publish step as a prerequisite so a single `gradle quarkusBuild`
// always works from a clean state.
val publishExtension = listOf(
    ":quarkus-smithy:publishToMavenLocal",
    ":quarkus-smithy-deployment:publishToMavenLocal",
)
tasks.matching { it.name in setOf("quarkusGenerateAppModel", "quarkusBuild", "quarkusDev", "quarkusRun") }
    .configureEach {
        publishExtension.forEach { dependsOn(it) }
    }

// .smithy files live under src/main/smithy/ — Quarkus's CodeGenProvider
// finds them automatically and IntelliJ's Gradle import surfaces them
// without extra source-set wiring.
