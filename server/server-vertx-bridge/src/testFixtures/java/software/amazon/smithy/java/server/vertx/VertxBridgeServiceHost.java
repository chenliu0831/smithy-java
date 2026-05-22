/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import software.amazon.smithy.java.protocoltests.harness.Ports;
import software.amazon.smithy.java.protocoltests.harness.ServiceHost;
import software.amazon.smithy.java.server.Service;

/**
 * {@link ServiceHost} that mounts a Smithy {@link Service} on a plain
 * Vert.x {@link Router} via {@link SmithyServiceBridge} and exposes
 * it on a free local port.
 *
 * <p>Selected by setting {@code -Dsmithy.protocoltest.host=vertx-bridge}.
 * Registered via {@code META-INF/services/} in this module's
 * test-fixtures so consumers opt in by adding the test-fixtures
 * artifact to their {@code itRuntimeOnly} configuration.
 *
 * <p>Per ADR-0007 §4 / §7: this host owns its own port picking and
 * delegates wire-version negotiation entirely to the Vert.x server.
 */
public final class VertxBridgeServiceHost implements ServiceHost {

    private Vertx vertx;
    private HttpServer http;
    private BoundBridge bound;

    @Override
    public String name() {
        return "vertx-bridge";
    }

    @Override
    public URI start(Service service) {
        java.util.Objects.requireNonNull(service, "service");
        if (vertx != null) {
            throw new IllegalStateException(
                    "VertxBridgeServiceHost.start() called twice without an intervening stop(); "
                            + "would leak the previously-started Vert.x instance and bridge.");
        }
        this.vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        this.bound = SmithyServiceBridge.bridge(List.of(service)).bind(router);
        int port = Ports.free();
        this.http = vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return URI.create("http://localhost:" + port);
    }

    @Override
    public void stop() {
        try {
            if (http != null) {
                http.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                http = null;
            }
            if (bound != null) {
                bound.unbind();
                try {
                    bound.shutdown().get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // best-effort orchestrator drain
                }
                bound = null;
            }
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                vertx = null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
