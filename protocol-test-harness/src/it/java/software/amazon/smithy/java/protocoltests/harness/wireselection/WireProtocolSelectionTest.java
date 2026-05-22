/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness.wireselection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smithy.test.wireselection.model.EchoOutput;
import smithy.test.wireselection.service.EchoOperation;
import smithy.test.wireselection.service.MultiProtocol;
import software.amazon.smithy.java.protocoltests.harness.ServiceHost;
import software.amazon.smithy.java.server.Service;

/**
 * Spec-compliance tests for §Server protocol selection from
 * <a href="https://smithy.io/2.0/guides/wire-protocol-selection.html">
 * Smithy 2.0 Wire protocol selection</a>:
 *
 * <ol>
 *   <li>"Servers MUST maintain a precision ordered list of protocols
 *       they support at runtime."
 *   <li>"To determine which protocol an inbound input utilizes, the
 *       service MUST iterate through the list."
 *   <li>"Services MUST reject the input if no suitable protocol that
 *       the service supports is identified."
 * </ol>
 *
 * <p>Each test asserts {@link ServiceHost} responses against goldens
 * captured from {@code :server:server-netty} (the reference
 * implementation). Strict assertions: exact status code, exact
 * Content-Type (or its documented absence), exact body bytes for
 * success paths. The bridge — or any other host — MUST match Netty's
 * behavior to be considered spec-compliant.
 *
 * <p>The {@code MultiProtocol} service is generated from
 * {@code src/it/resources/META-INF/smithy/wireselection/multiprotocol.smithy}.
 * It carries both {@code @restJson1} and {@code @rpcv2Cbor} traits with
 * one {@code Echo} operation declaring {@code @http(method: POST, uri: /echo)}.
 *
 * <p>Goldens were captured by running these probes against
 * {@code NettyServiceHost} in a one-time helper (no longer in the
 * tree; documented in commit history). To re-capture if Netty's
 * behavior shifts: temporarily re-run the equivalent probes and
 * print {@code response.statusCode()},
 * {@code response.headers().firstValue("Content-Type")}, and
 * {@code response.body()} as a hex dump.
 *
 * <p>Multi-host execution: the same test class runs against every
 * {@code ServiceHost} on the test classpath; selection is via
 * {@code -Dsmithy.protocoltest.host=<name>} (default {@code netty}).
 * The {@code :integ} task runs Netty (sanity-checks the goldens
 * against the source); {@code :integVertx} runs the Vert.x bridge
 * host.
 */
class WireProtocolSelectionTest {

    // ---------------- Goldens (captured from NettyServiceHost) ----------------

    /** Test 1 Probe A: restJson1 success — empty JSON object body. */
    private static final int TEST1_PROBE_A_STATUS = 200;
    private static final String TEST1_PROBE_A_CONTENT_TYPE = "application/json";
    private static final byte[] TEST1_PROBE_A_BODY = new byte[] {0x7B, 0x7D}; // {}

    /**
     * Test 1 Probe B: rpcv2-cbor success — empty map encoded as a
     * CBOR indefinite-length map: 0xBF (begin) 0xFF (break). This is
     * what Smithy-Java's rpcv2-cbor server emits for a zero-member
     * EchoOutput; clients accept either form (definite 0xA0 or
     * indefinite 0xBF...0xFF).
     */
    private static final int TEST1_PROBE_B_STATUS = 200;
    private static final String TEST1_PROBE_B_CONTENT_TYPE = "application/cbor";
    private static final byte[] TEST1_PROBE_B_BODY = new byte[] {(byte) 0xBF, (byte) 0xFF};

    /**
     * Test 5 — rpcv2-json success: empty JSON object {} body.
     * rpcv2-json shares the URI shape of rpcv2-cbor and is
     * disambiguated solely by the smithy-protocol header.
     */
    private static final int TEST5_STATUS = 200;
    private static final String TEST5_CONTENT_TYPE = "application/json";
    private static final byte[] TEST5_BODY = new byte[] {0x7B, 0x7D};

    /**
     * Test 6 — rpcv2-cbor still works once rpcv2-json is also
     * registered. Identical to Test 1 Probe B's golden; this is a
     * regression check that adding rpcv2-json to the service didn't
     * break rpcv2-cbor's bucket dispatch.
     */
    private static final int TEST6_STATUS = TEST1_PROBE_B_STATUS;
    private static final String TEST6_CONTENT_TYPE = TEST1_PROBE_B_CONTENT_TYPE;
    private static final byte[] TEST6_BODY = TEST1_PROBE_B_BODY;

    /**
     * Test 4: truly unidentifiable input. No registered protocol
     * claims the request (path doesn't match restJson1's @http; path
     * shape isn't rpcv2). Netty rejects with a bare 404, no
     * Content-Type, empty body. The spec mandates rejection
     * ("Services MUST reject the input if no suitable protocol...
     * is identified") but does not specify the rejection body shape;
     * Netty's transport returns a status-only response when no
     * operation is matched.
     */
    private static final int REJECTION_STATUS = 404;

    /**
     * Test 2: a request that *is* claimed by a protocol (rpcv2-json
     * matches the URI shape and the smithy-protocol header), but the
     * body's Content-Type doesn't match the protocol's expected
     * media type. Netty produces a Smithy-shaped 400 error: JSON
     * body containing the message "Invalid content type". Because
     * rpcv2-json owns this request shape, the response is a real
     * error response, not the bare 404 of unidentified rejection.
     */
    private static final int TEST2_STATUS = 400;
    private static final String TEST2_CONTENT_TYPE = "application/json";
    private static final byte[] TEST2_BODY =
            "{\"message\":\"Invalid content type\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Test 3: identical to Test 1 Probe A. restJson1 claims after rpcv2 declines. */
    private static final int TEST3_STATUS = TEST1_PROBE_A_STATUS;
    private static final String TEST3_CONTENT_TYPE = TEST1_PROBE_A_CONTENT_TYPE;
    private static final byte[] TEST3_BODY = TEST1_PROBE_A_BODY;

    // ---------------- Test infra ----------------

    private final AtomicReference<String> lastInvoked = new AtomicReference<>();
    private ServiceHost host;
    private URI endpoint;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        EchoOperation echo = (input, ctx) -> {
            lastInvoked.set("Echo");
            return EchoOutput.builder().build();
        };
        Service service = MultiProtocol.builder()
                .addEchoOperation(echo)
                .build();
        host = lookupHost();
        endpoint = host.start(service);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.stop();
        }
    }

    // ---------------- Tests ----------------

    @Test
    @DisplayName("Test 1: a multi-protocol service routes each request via the matching protocol")
    void multiProtocolServiceRoutesByProtocol() throws Exception {
        // Probe A — restJson1 identification: POST /echo + JSON body.
        HttpResponse<byte[]> a = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build());

        assertResponse("Probe A (restJson1)", a,
                TEST1_PROBE_A_STATUS, TEST1_PROBE_A_CONTENT_TYPE, TEST1_PROBE_A_BODY);
        assertThat(lastInvoked.getAndSet(null))
                .as("Probe A must invoke the modeled operation")
                .isEqualTo("Echo");

        // Probe B — rpcv2-cbor identification: POST the rpcv2 path
        // with the smithy-protocol header. Body is a one-byte 0xA0
        // (definite-length empty map). We send 0xA0 instead of zero
        // bytes to sidestep a known empty-body deserialization bug
        // in the CBOR codec — see RpcV2CborProtocolTests's "no_input"
        // skip list with the comment "TODO fix empty body handling
        // in the deserializer".
        HttpResponse<byte[]> b = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/service/MultiProtocol/operation/Echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xA0}))
                .header("Content-Type", "application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .build());

        assertResponse("Probe B (rpcv2-cbor)", b,
                TEST1_PROBE_B_STATUS, TEST1_PROBE_B_CONTENT_TYPE, TEST1_PROBE_B_BODY);
        assertThat(lastInvoked.get())
                .as("Probe B must invoke the modeled operation")
                .isEqualTo("Echo");
    }

    @Test
    @DisplayName("Test 2: rpcv2-json claims a request but rejects mismatched Content-Type with a 400")
    void rpcv2JsonRejectsMismatchedContentType() throws Exception {
        // Now that rpcv2-json is registered on the service, this
        // request IS claimed by a protocol — rpcv2-json owns the URI
        // shape and the smithy-protocol header. But the body is
        // CBOR-shaped (Content-Type: application/cbor), which is
        // rpcv2-json's *wrong* media type. The protocol's
        // deserializeInput should reject with MalformedRequestException
        // and produce a Smithy-shaped JSON 400 — not the bare 404 of
        // "no protocol matched."
        //
        // This is a meaningful spec assertion: the server identifies
        // the protocol via header but still validates that the body
        // shape matches the protocol's expectations. Pre-Slice-7 (no
        // rpcv2-json) the same probe was rejected at the routing
        // layer (404); now it's rejected at the deserialization
        // layer (400) with a structured error message.
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/service/MultiProtocol/operation/Echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xA0}))
                .header("Content-Type", "application/cbor")
                .header("smithy-protocol", "rpc-v2-json")
                .build());

        assertResponse("rpcv2-json with mismatched Content-Type", response,
                TEST2_STATUS, TEST2_CONTENT_TYPE, TEST2_BODY);
        assertThat(lastInvoked.get())
                .as("operation handler must not run when body content-type is wrong")
                .isNull();
    }

    @Test
    @DisplayName("Test 3: precision fall-through — rpcv2 declines missing-header request, restJson1 takes it")
    void precisionFallthroughToRestJson1() throws Exception {
        // Per spec the server iterates protocols in precision order
        // (rpcv2Cbor outranks restJson1 in the AWS list). A request
        // to /echo with no smithy-protocol header is shaped like
        // restJson1 and ineligible for rpcv2-cbor — rpcv2 must get
        // first refusal but decline (no smithy-protocol header), and
        // restJson1 must then claim it.
        //
        // This test catches the precision-sort bug only at its worst:
        // if restJson1 isn't reached at all when present, this fails.
        // It does not catch full tie-breaking precision (which would
        // require two protocols whose identification characteristics
        // overlap — neither restJson1 nor rpcv2-cbor have such an
        // overlap with each other).
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build());

        assertResponse("fall-through to restJson1", response, TEST3_STATUS, TEST3_CONTENT_TYPE, TEST3_BODY);
        assertThat(lastInvoked.get())
                .as("the operation must be invoked exactly once")
                .isEqualTo("Echo");
    }

    @Test
    @DisplayName("Test 4: a request matching no supported protocol is rejected")
    void unidentifiableRequestIsRejected() throws Exception {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/entirely-unknown"))
                .POST(HttpRequest.BodyPublishers.ofString("not-a-protocol"))
                .header("Content-Type", "application/octet-stream")
                .build());

        assertRejection("unidentifiable input", response);
        assertThat(lastInvoked.get())
                .as("operation handler must not run for unidentifiable input")
                .isNull();
    }

    @Test
    @DisplayName("Test 5: rpcv2-json probe is disambiguated from rpcv2-cbor by the smithy-protocol header on the same URI")
    void rpcv2JsonProbeRoutesByHeader() throws Exception {
        // rpcv2-cbor and rpcv2-json share the same URI shape:
        //   POST /service/<Name>/operation/<Op>
        // The server MUST disambiguate them by the smithy-protocol
        // request header. This is the canonical case for the
        // bridge's bucket-dispatch logic — the only place in the
        // tree where two protocols register routes for the same
        // (method, path) tuple.
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/service/MultiProtocol/operation/Echo"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .header("smithy-protocol", "rpc-v2-json")
                .build());

        assertResponse("rpcv2-json", response, TEST5_STATUS, TEST5_CONTENT_TYPE, TEST5_BODY);
        assertThat(lastInvoked.get())
                .as("rpcv2-json dispatch must invoke the modeled operation")
                .isEqualTo("Echo");
    }

    @Test
    @DisplayName("Test 6: rpcv2-cbor still works after rpcv2-json is added (regression)")
    void rpcv2CborStillWorksWithRpcv2JsonRegistered() throws Exception {
        // Companion to Test 5: confirm that adding rpcv2-json to the
        // service didn't shadow rpcv2-cbor. Sends the same probe as
        // Test 1 Probe B, but in a context where the bucket holds
        // both rpcv2 protocols. If the bucket-dispatch logic
        // accidentally short-circuits on the first protocol in the
        // bucket without consulting headers, this test fails.
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder()
                .uri(endpoint.resolve("/service/MultiProtocol/operation/Echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xA0}))
                .header("Content-Type", "application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .build());

        assertResponse("rpcv2-cbor (with rpcv2-json also registered)", response,
                TEST6_STATUS, TEST6_CONTENT_TYPE, TEST6_BODY);
        assertThat(lastInvoked.get())
                .as("rpcv2-cbor dispatch must invoke the modeled operation")
                .isEqualTo("Echo");
    }

    // ---------------- Assertion helpers ----------------

    /**
     * Strict success-response assertion: exact status, exact
     * Content-Type, exact body bytes. Any divergence between the
     * host under test and Netty surfaces as a clear comparison.
     */
    private static void assertResponse(
            String label,
            HttpResponse<byte[]> actual,
            int expectedStatus,
            String expectedContentType,
            byte[] expectedBody) {
        assertThat(actual.statusCode())
                .as("[%s] status code", label)
                .isEqualTo(expectedStatus);
        assertThat(actual.headers().firstValue("Content-Type"))
                .as("[%s] Content-Type header", label)
                .isPresent()
                .hasValue(expectedContentType);
        assertThat(actual.body())
                .as("[%s] response body bytes", label)
                .containsExactly(expectedBody);
    }

    /**
     * Strict rejection-response assertion: exact status (404), no
     * Content-Type header, empty body. Matches Netty's transport-level
     * "no operation matched" response shape.
     */
    private static void assertRejection(String label, HttpResponse<byte[]> actual) {
        assertThat(actual.statusCode())
                .as("[%s] rejection status code", label)
                .isEqualTo(REJECTION_STATUS);
        assertThat(actual.headers().firstValue("Content-Type"))
                .as("[%s] rejection Content-Type must be absent", label)
                .isEqualTo(Optional.empty());
        assertThat(actual.body())
                .as("[%s] rejection body must be empty", label)
                .isEmpty();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ServiceHost lookupHost() {
        String requested = System.getProperty("smithy.protocoltest.host", "netty");
        var available = new java.util.ArrayList<String>();
        for (var p : ServiceLoader.load(ServiceHost.class, ServiceHost.class.getClassLoader())) {
            if (p.name().equals(requested)) {
                return p;
            }
            available.add(p.name());
        }
        throw new IllegalStateException(
                "No ServiceHost named '" + requested + "'. Available: " + available);
    }
}
