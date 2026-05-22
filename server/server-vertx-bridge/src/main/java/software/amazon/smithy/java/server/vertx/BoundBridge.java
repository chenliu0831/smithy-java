/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import io.vertx.ext.web.Route;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import software.amazon.smithy.java.logging.InternalLogger;
import software.amazon.smithy.java.server.core.OrchestratorGroup;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * The handle returned by {@link SmithyServiceBridge#bind}. Owns the
 * routes the bridge installed on the router and the orchestrator group
 * the bridge constructed; both are released by {@link #unbind()} and
 * {@link #shutdown()} respectively.
 */
@SmithyUnstableApi
public final class BoundBridge {

    private static final InternalLogger LOG = InternalLogger.getLogger(BoundBridge.class);

    private final List<Route> routes;
    private final OrchestratorGroup orchestrator;
    private final Duration shutdownGrace;
    private volatile boolean unbound;
    private volatile CompletableFuture<Void> shutdownFuture;

    BoundBridge(List<Route> routes, OrchestratorGroup orchestrator, Duration shutdownGrace) {
        this.routes = List.copyOf(routes);
        this.orchestrator = orchestrator;
        this.shutdownGrace = shutdownGrace;
    }

    /**
     * Atomically remove every route the bridge installed on its router.
     * Idempotent. After {@code unbind()} returns, requests on these
     * paths fall through to subsequent routes via Vert.x's standard
     * routing.
     */
    public synchronized void unbind() {
        if (unbound) {
            return;
        }
        unbound = true;
        for (Route r : routes) {
            try {
                r.remove();
            } catch (Exception e) {
                LOG.warn("Failed to remove route {} during unbind", r, e);
            }
        }
    }

    /**
     * Best-effort shutdown of the orchestrator the bridge constructed.
     *
     * <p><b>Caveat:</b> the underlying {@link OrchestratorGroup}'s
     * shutdown semantics are inherited from
     * {@code SingleThreadOrchestrator.shutdown()}, which currently
     * resolves immediately and relies on the worker being a daemon
     * thread that stops with the JVM. The {@code shutdownGrace}
     * configured via {@link BridgeOptions} bounds the wait but does
     * not guarantee in-flight work completes. This API contract is
     * expected to tighten when {@code SingleThreadOrchestrator}
     * implements proper drain semantics.
     *
     * <p>The future this method returns is idempotent: subsequent
     * calls return the same future.
     */
    public synchronized CompletableFuture<Void> shutdown() {
        if (shutdownFuture != null) {
            return shutdownFuture;
        }
        CompletableFuture<Void> drain = orchestrator.shutdown();
        // Bound the wait so a stuck handler can't hold up Quarkus shutdown.
        CompletableFuture<Void> deadline = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(shutdownGrace.toMillis());
                deadline.complete(null);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                deadline.complete(null);
            }
        }, "smithy-bridge-shutdown-deadline");
        t.setDaemon(true);
        t.start();
        shutdownFuture = CompletableFuture.anyOf(drain, deadline).thenAccept(ignored -> {});
        return shutdownFuture;
    }

    /** Visible for the bridge's own tests; consumers should not depend on this. */
    int boundRouteCount() {
        return routes.size();
    }

    /** Visible for the bridge's own tests; consumers should not depend on this. */
    boolean isUnbound() {
        return unbound;
    }
}
