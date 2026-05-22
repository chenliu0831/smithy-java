

plugins {
    id("smithy-java.java-conventions")
    id("smithy-java.integ-test-conventions")
}

description = "This module provides a test harness and tools for executing protocol tests."

dependencies {
    implementation(project(":logging"))
    implementation(project(":codegen:codegen-plugin"))
    implementation(libs.smithy.codegen)
    implementation(project(":client:client-core"))
    implementation(libs.smithy.protocol.test.traits)
    implementation(project(":http:http-api"))
    implementation(project(":server:server-api"))
    implementation(project(":server:server-core"))
    implementation(project(":client:client-http"))
    implementation(project(":codecs:json-codec", configuration = "shadow"))
    implementation(libs.assertj.core)

    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
    api(libs.junit.jupiter.engine)
    api(libs.junit.jupiter.params)

    // ADR-0007 Slice 6: Wire-protocol-selection compliance tests.
    //
    // The MultiProtocol .smithy model under
    // src/it/resources/META-INF/smithy/wireselection/ uses @restJson1
    // and @rpcv2Cbor traits. The trait jars are needed at codegen
    // time (the task runs against the test classpath) AND at test
    // execution time (the runtime ServerProtocolProvider lookup
    // depends on the same trait classes).
    testImplementation(libs.smithy.aws.traits)
    testImplementation(libs.smithy.protocol.traits)
    testImplementation(project(":aws:server:aws-server-restjson"))
    testImplementation(project(":server:server-rpcv2-cbor"))
    testImplementation(project(":server:server-rpcv2-json"))

    // NettyServiceHost calls Server.builder(), which discovers a
    // ServerProvider via ServiceLoader. Without this dep, "Couldn't
    // find a server provider" at start time.
    itRuntimeOnly(project(":server:server-netty"))

    // integVertx Gradle variant runs the same tests against the
    // Vert.x bridge host. Adds the bridge testFixtures so
    // VertxBridgeServiceHost is registered via META-INF/services.
    itRuntimeOnly(testFixtures(project(":server:server-vertx-bridge")))
}

// ADR-0007 Slice 6: codegen for the multi-protocol wire-selection
// fixture. Pattern matches the per-protocol modules
// (aws-server-restjson, server-rpcv2-cbor, server-rpcv2-json) — the
// .smithy model lives under src/it/resources/META-INF/smithy/, the
// codegen task generates Java service stubs into the `it` source
// set, and the wire-selection tests use the generated stubs the
// same way ProtocolTestExtension uses generated stubs for canonical
// protocol tests.
val protocolTestGenerator = "software.amazon.smithy.java.protocoltests.generators.ProtocolTestGenerator"
addGenerateSrcsTask(
        protocolTestGenerator,
        "wireselection",
        "smithy.test.wireselection#MultiProtocol",
        "server")

// ADR-0007 Slice 6: secondary integ task that runs the same wire-
// selection tests against the Vert.x bridge host. Mirrors the
// per-protocol-module :integ / :integVertx pattern.
val integVertx by tasks.registering(Test::class) {
    description = "Re-runs harness-internal integration tests against the Vert.x bridge host."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["it"].output.classesDirs
    classpath = sourceSets["it"].runtimeClasspath
    systemProperty("smithy.protocoltest.host", "vertx-bridge")
}
tasks["test"].finalizedBy(integVertx)
