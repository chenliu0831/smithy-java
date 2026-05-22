plugins {
    id("smithy-java.module-conventions")
    id("smithy-java.protocol-testing-conventions")
}

description = "This module provides the RpcV2 CBOR support for servers."

extra["displayName"] = "Smithy :: Java :: Server :: RPCv2 CBOR"
extra["moduleName"] = "software.amazon.smithy.java.server.rpcv2cbor"

dependencies {
    api(project(":server:server-rpcv2"))
    api(libs.smithy.protocol.traits)
    implementation(project(":codecs:cbor-codec"))

    itImplementation(project(":server:server-api"))
    itImplementation(project(":server:server-netty"))
    itImplementation(project(":client:client-rpcv2-cbor"))
    itImplementation(testFixtures(project(":codecs:cbor-codec")))
    // ADR-0007: VertxBridgeServiceHost is selected at runtime by
    // -Dsmithy.protocoltest.host=vertx-bridge in the integVertx task.
    itRuntimeOnly(testFixtures(project(":server:server-vertx-bridge")))

    // Protocol test dependencies
    testImplementation(libs.smithy.aws.protocol.tests)
    testImplementation(libs.smithy.protocol.tests)
}

val generator = "software.amazon.smithy.java.protocoltests.generators.ProtocolTestGenerator"
addGenerateSrcsTask(generator, "rpcv2Cbor", "smithy.protocoltests.rpcv2Cbor#RpcV2Protocol", "server")

// ADR-0007 Phase 1: re-runs the same `it` test classes against the
// Vert.x bridge host. Skip-list divergence (if any) between :integ
// (Netty) and :integVertx is the protocol-compliance signal.
val integVertx by tasks.registering(Test::class) {
    description = "Re-runs server-side protocol tests against the Vert.x bridge."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["it"].output.classesDirs
    classpath = sourceSets["it"].runtimeClasspath
    systemProperty("smithy.protocoltest.host", "vertx-bridge")
}
tasks["test"].finalizedBy(integVertx)
