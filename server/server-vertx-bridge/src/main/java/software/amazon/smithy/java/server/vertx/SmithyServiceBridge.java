/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.smithy.java.logging.InternalLogger;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.java.server.core.ErrorHandlingOrchestrator;
import software.amazon.smithy.java.server.core.HandlerAssembler;
import software.amazon.smithy.java.server.core.OrchestratorGroup;
import software.amazon.smithy.java.server.core.RouteSpec;
import software.amazon.smithy.java.server.core.ServerProtocol;
import software.amazon.smithy.java.server.core.ServerProtocolProvider;
import software.amazon.smithy.java.server.core.SingleThreadOrchestrator;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Mounts Smithy {@link Service} operations on a Vert.x {@link Router}.
 *
 * <p>The bridge enumerates each operation's {@link RouteSpec} via the
 * registered {@link ServerProtocol}s (e.g., restJson1's {@code @http}
 * traits, rpcv2's computed {@code /service/<Name>/operation/<Op>}), and
 * registers one Vert.x route per spec. Paths the user has *not*
 * declared on a Smithy operation never enter the bridge: standard
 * Vert.x routing dispatches them to whatever else the router has.
 *
 * <p>The bridge accepts both HTTP/1.1 and HTTP/2 traffic; wire-version
 * negotiation is delegated entirely to the Vert.x server hosting the
 * router (so that ALPN, h2c upgrade, and TLS choices live with the
 * server, not with us). Response framing flows through Vert.x's
 * {@link io.vertx.core.http.HttpServerResponse}, preserving the
 * version Vert.x parsed in.
 *
 * <p>Body buffering uses Vert.x's {@link BodyHandler}; the bridge does
 * not currently support {@code @streaming Blob} operations.
 *
 * <p>Reusable beyond Quarkus: any Vert.x application (plain Vert.x,
 * Lambda Function URL routed via Vert.x, ...) can mount Smithy
 * operations on its router.
 */
@SmithyUnstableApi
public final class SmithyServiceBridge {

    private static final InternalLogger LOG = InternalLogger.getLogger(SmithyServiceBridge.class);

    private final List<Service> services;
    private final BridgeOptions options;

    private SmithyServiceBridge(List<Service> services, BridgeOptions options) {
        this.services = List.copyOf(services);
        this.options = options;
    }

    /**
     * Construct a bridge with default options.
     */
    public static SmithyServiceBridge bridge(List<Service> services) {
        return bridge(services, BridgeOptions.defaults());
    }

    /**
     * Construct a bridge with custom options.
     */
    public static SmithyServiceBridge bridge(List<Service> services, BridgeOptions options) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(options, "options");
        if (services.isEmpty()) {
            throw new IllegalArgumentException(
                    "SmithyServiceBridge requires at least one Service. "
                            + "Add @Produces Service beans (Quarkus) or pass them directly.");
        }
        return new SmithyServiceBridge(services, options);
    }

    /**
     * Mount each operation as its own Vert.x route on the supplied
     * router. Returns a {@link BoundBridge} that owns the installed
     * routes and the orchestrator the bridge constructed.
     *
     * @throws BindException if two operations on different services
     *     resolve to the same {@code (HTTP method, path)} pair.
     */
    public BoundBridge bind(Router router) {
        Objects.requireNonNull(router, "router");

        var protocols = loadServerProtocols(services);
        if (protocols.isEmpty()) {
            throw new IllegalStateException(
                    "No ServerProtocol implementations found on the classpath. "
                            + "Add the protocol module(s) your services declare (e.g. "
                            + "aws-server-restjson, server-rpcv2-cbor) to the runtime "
                            + "classpath.");
        }

        // Enumerate per-protocol routes. Group by (method, path) so
        // multiple protocols owning the same path (e.g., rpcv2-cbor +
        // rpcv2-json both claim /service/X/operation/Y, distinguished
        // by the smithy-protocol header) collapse into one Vert.x
        // route whose handler picks the protocol per request.
        // Real collisions — two *operations* on the same path — fail
        // fast at bind time.
        record RouteKey(String method, String path) {}
        Map<RouteKey, List<RouteSpecWithProtocol>> grouped = new HashMap<>();
        List<RouteKey> orderedKeys = new ArrayList<>();
        for (ServerProtocol protocol : protocols) {
            for (RouteSpec spec : protocol.enumerateRoutes()) {
                String method = spec.httpMethod();
                String fullPath = applyPrefix(spec.path(), options.pathPrefix());
                var key = new RouteKey(method, fullPath);
                var bucket = grouped.computeIfAbsent(key, k -> {
                    orderedKeys.add(k);
                    return new ArrayList<>();
                });
                // Detect operation-level collision: two distinct
                // operations on the same (method, path).
                for (var existing : bucket) {
                    if (existing.spec.operation() != spec.operation()) {
                        throw new BindException(
                                "Route collision: "
                                        + method + " " + fullPath
                                        + " is declared by both '"
                                        + existing.spec.service().schema().id() + "#"
                                        + existing.spec.operation().name()
                                        + "' and '"
                                        + spec.service().schema().id() + "#"
                                        + spec.operation().name()
                                        + "'.");
                    }
                }
                bucket.add(new RouteSpecWithProtocol(spec, protocol, fullPath));
            }
        }

        var orchestrator = newOrchestrator();

        BodyHandler bodyHandler = BodyHandler.create();
        List<Route> installed = new ArrayList<>();
        try {
            for (RouteKey key : orderedKeys) {
                List<RouteSpecWithProtocol> bucket = grouped.get(key);
                HttpMethod m = HttpMethod.valueOf(key.method());
                // All entries in a bucket share (method, path) AND
                // operation; only the protocol differs. The dispatch
                // tries each protocol's resolveOperation in order;
                // first that returns non-null wins.
                Route route = router.route(m, key.path())
                        .handler(bodyHandler)
                        .handler(new OperationDispatch(
                                bucket.get(0).spec.service(),
                                bucket.get(0).spec.operation(),
                                bucket.stream().map(RouteSpecWithProtocol::protocol).toList(),
                                orchestrator,
                                options.pathPrefix()));
                installed.add(route);
                if (bucket.size() == 1) {
                    LOG.info(
                            "Mounted Smithy operation {}#{} on {} {}",
                            bucket.get(0).spec.service().schema().id(),
                            bucket.get(0).spec.operation().name(),
                            key.method(),
                            key.path());
                } else {
                    LOG.info(
                            "Mounted Smithy operation {}#{} on {} {} (multi-protocol: {})",
                            bucket.get(0).spec.service().schema().id(),
                            bucket.get(0).spec.operation().name(),
                            key.method(),
                            key.path(),
                            bucket.size());
                }
            }
        } catch (Throwable t) {
            // Roll back any partial installation.
            for (Route route : installed) {
                try {
                    route.remove();
                } catch (Exception ignored) {}
            }
            try {
                orchestrator.shutdown().get();
            } catch (Exception ignored) {}
            throw t;
        }

        return new BoundBridge(installed, orchestrator, options.shutdownGrace());
    }

    private static String applyPrefix(String path, String prefix) {
        if (prefix.isEmpty()) {
            return path;
        }
        // Caller normalized prefix so it always begins with "/" and
        // never ends with "/".
        if (path.equals("/")) {
            return prefix;
        }
        return prefix + path;
    }

    private static List<ServerProtocol> loadServerProtocols(List<Service> services) {
        // Use the same SPI that ProtocolResolver does, but instantiate
        // each protocol once over the union of services. The bridge
        // does not need a ProtocolResolver — routing is by
        // enumeration, not by per-request resolution.
        //
        // Resolve providers from both the thread-context classloader
        // (Quarkus's QuarkusClassLoader at recorder time, which sees
        // every runtime jar) and the bridge's own loader, deduped by
        // provider class. Falling back to a single loader is fragile
        // under containers/frameworks that partition app classes.
        List<ServerProtocol> out = new ArrayList<>();
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            collectProviders(services, tccl, out, seen);
        }
        ClassLoader own = SmithyServiceBridge.class.getClassLoader();
        if (own != tccl) {
            collectProviders(services, own, out, seen);
        }
        return Collections.unmodifiableList(out);
    }

    private static void collectProviders(
            List<Service> services,
            ClassLoader cl,
            List<ServerProtocol> out,
            java.util.Set<Class<?>> seen) {
        for (var provider : java.util.ServiceLoader.load(ServerProtocolProvider.class, cl)) {
            if (seen.add(provider.getClass())) {
                out.add(provider.provideProtocolHandler(services));
            }
        }
    }

    private OrchestratorGroup newOrchestrator() {
        var handlers = new HandlerAssembler().assembleHandlers(services);
        return new OrchestratorGroup(
                options.workerCount(),
                () -> new ErrorHandlingOrchestrator(new SingleThreadOrchestrator(handlers)),
                OrchestratorGroup.Strategy.roundRobin());
    }

    private record RouteSpecWithProtocol(RouteSpec spec, ServerProtocol protocol, String path) {}
}
