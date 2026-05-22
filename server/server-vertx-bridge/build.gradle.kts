plugins {
    id("smithy-java.module-conventions")
    `java-test-fixtures`
}

description = "Vert.x Router-mountable bridge for Smithy Java server operations"

extra["displayName"] = "Smithy :: Java :: Server :: Vert.x Bridge"
extra["moduleName"] = "software.amazon.smithy.java.server.vertx"

dependencies {
    api(project(":server:server-core"))
    implementation(project(":logging"))
    implementation(project(":context"))
    implementation(project(":http:http-api"))
    implementation(project(":io"))

    api(libs.vertx.core)
    api(libs.vertx.web)

    // VertxBridgeServiceHost lives in src/testFixtures/. Consumers of
    // protocol-test-harness opt into bridge hosting via
    // `itRuntimeOnly(testFixtures(project(":server:server-vertx-bridge")))`
    // and `-Dsmithy.protocoltest.host=vertx-bridge`. See ADR-0007.
    testFixturesApi(project(":protocol-test-harness"))
    testFixturesApi(project(":server:server-api"))

    // Bridge supports restJson1 + rpcv2 protocols at test time. Production
    // consumers add only the protocol jars they actually need.
    testImplementation(project(":server:server-rpcv2-cbor"))
    testImplementation(project(":server:server-rpcv2-json"))
    testImplementation(project(":aws:server:aws-server-restjson"))
    testImplementation(project(":codecs:json-codec"))
    testImplementation(project(":codecs:cbor-codec"))
    testImplementation(project(":core"))
    testImplementation(project(":http:http-binding"))
    testImplementation(libs.smithy.aws.traits)
    testImplementation(libs.smithy.protocol.traits)

    testImplementation(libs.vertx.junit5)
    testImplementation(libs.vertx.web.client)
    testImplementation(libs.assertj.core)
}

