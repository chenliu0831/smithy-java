/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.smithy.java.server.Service;
// Service is the canonical type, but tests use the MenuFixture.MenuService
// concrete type to assert on lastInvoked.

/**
 * Integration tests for {@link SmithyServiceBridge}. Each test spins up
 * a real Vert.x HTTP server, mounts the bridge on its router, and sends
 * real HTTP requests via {@link WebClient}.
 */
@ExtendWith(VertxExtension.class)
class SmithyServiceBridgeTest {

    private Vertx vertx;
    private HttpServer server;
    private WebClient client;
    private Router router;
    private int port;
    private BoundBridge bound;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) throws InterruptedException {
        this.vertx = vertx;
        this.router = Router.router(vertx);
        this.server = vertx.createHttpServer(new HttpServerOptions().setPort(0));
        server.requestHandler(router)
                .listen()
                .onComplete(ctx.succeedingThenComplete());
        ctx.awaitCompletion(5, TimeUnit.SECONDS);
        this.port = server.actualPort();
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bound != null) {
            bound.unbind();
            bound.shutdown().get(5, TimeUnit.SECONDS);
            bound = null;
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void getMenuRespondsWithSerializedOutput(VertxTestContext ctx) {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);

        client.get(port, "localhost", "/menu")
                .send()
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertThat(resp.statusCode()).isEqualTo(200);
                    // The operation must have been invoked end-to-end,
                    // not just the route mounted.
                    assertThat(menu.lastInvoked.get()).isEqualTo("GetMenu");
                    ctx.completeNow();
                })));
    }

    @Test
    void restJson1PathParametersAndMethodsRouteCorrectly(VertxTestContext ctx) {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);

        // GET /order/abc must route to GetOrder (the @http(uri:"/order/{id}")
        // operation, translated to Vert.x's /order/:id).
        client.get(port, "localhost", "/order/abc")
                .send()
                .onComplete(ctx.succeeding(resp1 -> ctx.verify(() -> {
                    assertThat(resp1.statusCode()).isEqualTo(200);
                    assertThat(menu.lastInvoked.get()).isEqualTo("GetOrder");

                    // PUT /order must route to PutOrder (different method,
                    // overlapping path prefix with the labeled route).
                    client.put(port, "localhost", "/order")
                            .sendBuffer(io.vertx.core.buffer.Buffer.buffer("{}"))
                            .onComplete(ctx.succeeding(resp2 -> ctx.verify(() -> {
                                assertThat(resp2.statusCode()).isEqualTo(200);
                                assertThat(menu.lastInvoked.get()).isEqualTo("PutOrder");
                                ctx.completeNow();
                            })));
                })));
    }

    @Test
    void rpcv2CborRequiresSmithyProtocolHeader(VertxTestContext ctx) {
        var ping = PingFixture.pingService();
        bound = SmithyServiceBridge.bridge(List.of(ping)).bind(router);

        // Request WITHOUT smithy-protocol header → 404
        // (rpcv2Cbor protocol's resolveOperation returns null when the
        // header is missing or wrong, the bridge translates that to
        // 404 via OperationDispatch.handle).
        client.post(port, "localhost", "/service/Ping/operation/Ping")
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(new byte[0]))
                .onComplete(ctx.succeeding(resp1 -> ctx.verify(() -> {
                    assertThat(resp1.statusCode()).isEqualTo(404);

                    // Request WITH the correct smithy-protocol header → 200
                    // Empty CBOR map (0xa0) is the canonical "empty struct"
                    // payload for rpcv2-cbor; an entirely empty body would
                    // fail CBOR deserialization.
                    var emptyCborMap = io.vertx.core.buffer.Buffer.buffer(new byte[]{(byte) 0xa0});
                    client.post(port, "localhost", "/service/Ping/operation/Ping")
                            .putHeader("smithy-protocol", "rpc-v2-cbor")
                            .putHeader("content-type", "application/cbor")
                            .sendBuffer(emptyCborMap)
                            .onComplete(ctx.succeeding(resp2 -> ctx.verify(() -> {
                                assertThat(resp2.statusCode()).isEqualTo(200);
                                assertThat(ping.lastInvoked.get()).isEqualTo("Ping");
                                ctx.completeNow();
                            })));
                })));
    }

    @Test
    void multipleServicesAcrossProtocolsCoexist(VertxTestContext ctx) {
        // restJson1 menu service AND rpcv2-cbor ping service share the
        // same router. Each operation gets its own Vert.x route from the
        // appropriate protocol; non-overlapping paths don't collide.
        var menu = MenuFixture.menuService();
        var ping = PingFixture.pingService();
        bound = SmithyServiceBridge.bridge(List.of(menu, ping)).bind(router);

        // Hit the restJson1 service.
        client.get(port, "localhost", "/menu")
                .send()
                .onComplete(ctx.succeeding(resp1 -> ctx.verify(() -> {
                    assertThat(resp1.statusCode()).isEqualTo(200);

                    // Hit the rpcv2-cbor service.
                    client.post(port, "localhost", "/service/Ping/operation/Ping")
                            .putHeader("smithy-protocol", "rpc-v2-cbor")
                            .putHeader("content-type", "application/cbor")
                            .sendBuffer(io.vertx.core.buffer.Buffer.buffer(new byte[]{(byte) 0xa0}))
                            .onComplete(ctx.succeeding(resp2 -> ctx.verify(() -> {
                                assertThat(resp2.statusCode()).isEqualTo(200);
                                assertThat(ping.lastInvoked.get()).isEqualTo("Ping");
                                ctx.completeNow();
                            })));
                })));
    }

    @Test
    void pathCollisionFailsFastAtBindTime() {
        // Two services declaring the same (method, path) pair must not
        // both mount on the same router; the bridge throws BindException
        // before any route is installed.
        var menu = MenuFixture.menuService();
        var collidingMenu = MenuFixture.collidingMenuService();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> SmithyServiceBridge.bridge(List.of(menu, collidingMenu)).bind(router))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("/menu")
                .hasMessageContaining("test#Menu")
                .hasMessageContaining("test#OtherMenu");

        // Router must be unchanged after a failed bind.
        assertThat(router.getRoutes()).isEmpty();
    }

    @Test
    void http2RequestGetsHttp2Response(VertxTestContext ctx) throws Exception {
        // Bring up a *separate* HTTP server with HTTP/2 (h2c) enabled,
        // so we can verify the bridge does not hard-code HTTP/1.1 in
        // its response framing. The setUp() server is HTTP/1-only.
        var h2cRouter = Router.router(vertx);
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(h2cRouter);

        var h2cServer = vertx.createHttpServer(new HttpServerOptions()
                .setPort(0)
                .setUseAlpn(false)
                // h2c (HTTP/2 over cleartext) is the simplest way to
                // assert HTTP/2 framing without TLS plumbing in tests.
                .setHttp2ClearTextEnabled(true)
                .addEnabledSecureTransportProtocol("TLSv1.3"));
        var listenFuture = h2cServer.requestHandler(h2cRouter).listen();
        listenFuture.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int h2cPort = h2cServer.actualPort();

        var h2cClient = WebClient.create(vertx, new io.vertx.ext.web.client.WebClientOptions()
                .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(true));

        h2cClient.get(h2cPort, "localhost", "/menu")
                .send()
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertThat(resp.statusCode()).isEqualTo(200);
                    // Vert.x's response.version() reflects the wire
                    // version the client received.
                    assertThat(resp.version()).isEqualTo(
                            io.vertx.core.http.HttpVersion.HTTP_2);
                    h2cClient.close();
                    h2cServer.close()
                            .toCompletionStage().toCompletableFuture()
                            .whenComplete((v, t) -> ctx.completeNow());
                })));
    }

    @Test
    void unbindRemovesAllInstalledRoutes(VertxTestContext ctx) {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);
        // Before unbind, /menu hits the bridge.
        client.get(port, "localhost", "/menu")
                .send()
                .onComplete(ctx.succeeding(resp1 -> ctx.verify(() -> {
                    assertThat(resp1.statusCode()).isEqualTo(200);

                    bound.unbind();
                    bound = null; // tearDown won't try to unbind again
                    // After unbind, /menu falls through; no other route
                    // installed, so Vert.x returns 404.
                    client.get(port, "localhost", "/menu")
                            .send()
                            .onComplete(ctx.succeeding(resp2 -> ctx.verify(() -> {
                                assertThat(resp2.statusCode()).isEqualTo(404);
                                ctx.completeNow();
                            })));
                })));
    }

    @Test
    void unbindIsIdempotent() {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);
        bound.unbind();
        // Second call must not throw.
        bound.unbind();
        bound = null;
    }

    @Test
    void shutdownReturnsCompletableFutureAndIsIdempotent() throws Exception {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);

        var f1 = bound.shutdown();
        // Should complete within the default 10s shutdown grace.
        f1.get(15, TimeUnit.SECONDS);
        assertThat(f1).isDone();

        // Calling shutdown again returns the *same* future (idempotent).
        var f2 = bound.shutdown();
        assertThat(f2).isSameAs(f1);

        bound = null;
    }

    @Test
    void pathPrefixOptionPrependsAllOperationRoutes(VertxTestContext ctx) {
        var menu = MenuFixture.menuService();
        var options = BridgeOptions.builder().pathPrefix("/api").build();
        bound = SmithyServiceBridge.bridge(List.of(menu), options).bind(router);

        // Operation declared at @http(uri:"/menu") is now reachable at
        // /api/menu, NOT at /menu.
        client.get(port, "localhost", "/api/menu")
                .send()
                .onComplete(ctx.succeeding(resp1 -> ctx.verify(() -> {
                    assertThat(resp1.statusCode()).isEqualTo(200);
                    assertThat(menu.lastInvoked.get()).isEqualTo("GetMenu");

                    client.get(port, "localhost", "/menu")
                            .send()
                            .onComplete(ctx.succeeding(resp2 -> ctx.verify(() -> {
                                assertThat(resp2.statusCode()).isEqualTo(404);
                                ctx.completeNow();
                            })));
                })));
    }

    @Test
    void workerCountOptionAcceptsCustomSize() {
        // We don't assert on thread count directly (orchestrator
        // internals aren't exposed); we assert the option is honored
        // by ensuring bind succeeds with a non-default value. Route
        // count is not asserted here because multiple protocols may
        // mount routes for the same service (e.g. restJson1 on
        // @http traits, rpcv2-cbor/json on POST /service/X/operation/Y),
        // and grouping by path is verified separately in
        // multipleServicesAcrossProtocolsCoexist.
        var menu = MenuFixture.menuService();
        var options = BridgeOptions.builder().workerCount(2).build();
        bound = SmithyServiceBridge.bridge(List.of(menu), options).bind(router);
        assertThat(bound.boundRouteCount()).isGreaterThan(0);
    }

    @Test
    void shutdownGraceBoundsShutdownDuration() throws Exception {
        var menu = MenuFixture.menuService();
        // 50ms grace — even if workers were stuck, shutdown returns
        // within 50ms + a small CI tolerance.
        var options = BridgeOptions.builder()
                .shutdownGrace(java.time.Duration.ofMillis(50))
                .build();
        bound = SmithyServiceBridge.bridge(List.of(menu), options).bind(router);

        long start = System.nanoTime();
        bound.shutdown().get(5, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Idle bridge drains immediately; this only catches a regression
        // where shutdown blocks past the grace.
        assertThat(elapsedMs).isLessThan(2_000L);

        bound = null;
    }

    /**
     * Forward-compat invariant for ADR-0003: BridgeOptions intentionally
     * exposes no interceptor field. Adding one later must remain a
     * non-breaking, additive change. This test is a tripwire: it locks
     * the current public API surface so any new method must be a
     * deliberate decision.
     */
    @Test
    void bridgeOptionsHasNoInterceptorField() {
        var declaredMethods = BridgeOptions.Builder.class.getDeclaredMethods();
        for (var m : declaredMethods) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            org.assertj.core.api.Assertions
                    .assertThat(name)
                    .as("BridgeOptions.Builder method '%s' must not advertise an interceptor", m.getName())
                    .doesNotContain("interceptor");
        }
    }

    /**
     * Regression for the bridge code review's B2 finding: when both
     * restJson1 and rpcv2 protocol jars are on the classpath but a
     * service only declares restJson1 operations (i.e., its operations
     * carry @http traits), the rpcv2 protocols must NOT mount phantom
     * /service/<Name>/operation/<Op> routes for that service.
     */
    @Test
    void restJson1OnlyServiceDoesNotGetPhantomRpcv2Routes(VertxTestContext ctx) {
        var menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);

        // The phantom mount, if it existed, would be at
        // POST /service/Menu/operation/GetMenu with smithy-protocol
        // header. We assert it 404s because no such route was
        // installed by either rpcv2 protocol.
        client.post(port, "localhost", "/service/Menu/operation/GetMenu")
                .putHeader("smithy-protocol", "rpc-v2-cbor")
                .putHeader("content-type", "application/cbor")
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(new byte[]{(byte) 0xa0}))
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertThat(resp.statusCode()).isEqualTo(404);
                    ctx.completeNow();
                })));
    }

    @Test
    void zeroServicesAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SmithyServiceBridge.bridge(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one Service");
    }

    @Test
    void unrelatedPathFallsThroughToUserRoute(VertxTestContext ctx) {
        // Install a user route on the same router *before* binding the
        // bridge — Vert.x route ordering is registration order, so the
        // bridge's routes append after this. The user route still wins
        // for paths the bridge does not own.
        router.get("/admin/health").handler(rc -> rc.response()
                .setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end("user-handler-ok"));

        Service menu = MenuFixture.menuService();
        bound = SmithyServiceBridge.bridge(List.of(menu)).bind(router);

        client.get(port, "localhost", "/admin/health")
                .send()
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertThat(resp.statusCode()).isEqualTo(200);
                    assertThat(resp.bodyAsString()).isEqualTo("user-handler-ok");
                    ctx.completeNow();
                })));
    }
}
