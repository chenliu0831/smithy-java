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
    // Types-only model — Instance<Server> resolves to nothing and the
    // startup/shutdown observers no-op.
    implementation("software.amazon.smithy.java:quarkus-smithy:$smithyJavaVersion")

    // Quarkus runtime + Vert.x HTTP for the @Route handler.
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-vertx-http")

    // Smithy-Java JSON codec to serialize/deserialize generated shapes on the
    // wire. The Types-only model intentionally avoids server-api / client-core,
    // so we use the codec directly inside the Vert.x route handler.
    //
    // core is transitive via the codec; declared explicitly only for clarity.
    implementation("software.amazon.smithy.java:core:$smithyJavaVersion")
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
// mavenLocal. json-codec is included because it publishes a shaded jar via
// shadowJar; without an explicit dependency, Gradle 9's strict task graph
// validation reports an implicit dependency error.
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
