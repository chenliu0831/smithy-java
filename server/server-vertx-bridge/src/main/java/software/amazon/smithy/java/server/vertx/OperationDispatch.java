/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.framework.model.UnknownOperationException;
import software.amazon.smithy.java.io.datastream.DataStream;
import software.amazon.smithy.java.logging.InternalLogger;
import software.amazon.smithy.java.server.Operation;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.java.server.core.HttpJob;
import software.amazon.smithy.java.server.core.HttpRequest;
import software.amazon.smithy.java.server.core.HttpResponse;
import software.amazon.smithy.java.server.core.OrchestratorGroup;
import software.amazon.smithy.java.server.core.ServerProtocol;
import software.amazon.smithy.java.server.core.ServiceProtocolResolutionRequest;

/**
 * Per-operation Vert.x route handler. Each instance is bound to exactly
 * one {@code (service, operation, protocol)} triple. The handler:
 *
 * <ol>
 *   <li>Buffers the request body (matching the Netty handler's
 *       behavior; see {@code HttpRequestHandler.java:60-84}).</li>
 *   <li>Constructs an {@link HttpJob} from the request.</li>
 *   <li>Enqueues it with the {@link OrchestratorGroup}.</li>
 *   <li>Writes the response back through Vert.x's
 *       {@link io.vertx.core.http.HttpServerResponse}, preserving the
 *       wire version (HTTP/1.1 or HTTP/2) Vert.x parsed.</li>
 * </ol>
 */
final class OperationDispatch implements Handler<RoutingContext> {

    private static final InternalLogger LOG = InternalLogger.getLogger(OperationDispatch.class);

    private final Service service;
    private final Operation<? extends SerializableStruct, ? extends SerializableStruct> operation;
    /**
     * One or more candidate protocols. Routes that share
     * {@code (method, path)} but differ in protocol (e.g., rpcv2-cbor
     * and rpcv2-json both claim {@code POST /service/X/operation/Y})
     * are merged into a single dispatcher whose handler tries each
     * protocol's {@code resolveOperation} in registration order. The
     * first that returns non-null wins.
     */
    private final List<ServerProtocol> protocols;
    private final OrchestratorGroup orchestrator;
    /**
     * Path prefix applied at bind time. Stripped from the incoming
     * request URI before invoking the protocol's resolver so the
     * protocol's matchers (built from raw {@code @http(uri:...)}
     * traits) work without prefix-awareness.
     */
    private final String pathPrefix;

    OperationDispatch(
            Service service,
            Operation<? extends SerializableStruct, ? extends SerializableStruct> operation,
            List<ServerProtocol> protocols,
            OrchestratorGroup orchestrator,
            String pathPrefix) {
        this.service = service;
        this.operation = operation;
        this.protocols = List.copyOf(protocols);
        this.orchestrator = orchestrator;
        this.pathPrefix = pathPrefix;
    }

    @Override
    public void handle(RoutingContext rc) {
        // The operation's owning service is unambiguous at this point —
        // the bridge's enumerateRoutes() pinned it. Body buffering uses
        // BodyHandler (mounted by the bridge); rc.body() is non-null
        // here for any successful Vert.x BodyHandler upstream.
        Buffer body = rc.body() == null ? null : rc.body().buffer();

        URI uri = parseRequestUri(rc, pathPrefix);
        var requestHeaders = new VertxRequestHeaders(rc.request().headers());

        var smithyRequest = new HttpRequest(requestHeaders, uri, rc.request().method().name());
        byte[] bytes = body == null ? new byte[0] : body.getBytes();
        smithyRequest.setDataStream(DataStream.ofBytes(bytes, requestHeaders.contentType()));

        // Some protocols (e.g., AwsRestJson1Protocol) read state from
        // the request context that they populate inside resolveOperation
        // — concretely, restJson1's ValuedMatch holding @httpLabel
        // bindings. Other protocols select the operation from headers
        // rather than from the URI (e.g., the protocol-test harness's
        // DelegatingServerProtocol, which keys off
        // x-protocol-test-operation). The bridge dispatches by route
        // enumeration but still needs to run resolveOperation per
        // request to populate context AND to pick the operation when
        // the route is shared across operations. We use the
        // resolution result's operation — not the bind-time-pinned
        // one — so header-routed protocols work. For URI-routed
        // protocols (restJson1, rpcv2) the result's operation equals
        // the pinned one, so behavior is unchanged.
        var resolutionRequest = new ServiceProtocolResolutionRequest(
                uri,
                requestHeaders,
                smithyRequest.context(),
                rc.request().method().name());
        ServerProtocol selectedProtocol = null;
        Operation<? extends SerializableStruct, ? extends SerializableStruct> selectedOperation = operation;
        for (ServerProtocol p : protocols) {
            try {
                var resolved = p.resolveOperation(resolutionRequest, List.of(service));
                if (resolved != null) {
                    selectedProtocol = p;
                    selectedOperation = resolved.operation();
                    break;
                }
            } catch (UnknownOperationException e) {
                // This protocol owns the path shape (e.g., rpcv2 path
                // syntax) but rejected this request. Try the next.
            }
        }
        if (selectedProtocol == null) {
            rc.response().setStatusCode(404).end();
            return;
        }

        var smithyResponse = new HttpResponse(new VertxResponseHeaders());

        @SuppressWarnings({"rawtypes", "unchecked"})
        HttpJob job = new HttpJob((Operation) selectedOperation, selectedProtocol, smithyRequest, smithyResponse);

        // Capture the Vert.x context now (request-side, on the event
        // loop) so the writeResponse callback — which runs on the
        // orchestrator's worker thread — can hand the response writes
        // back to the event loop. Vert.x's HttpServerResponse contract
        // is that mutations must run on the request's Context.
        Context vertxContext = Vertx.currentContext();
        orchestrator.enqueue(job).whenComplete((r, t) -> {
            if (vertxContext != null) {
                vertxContext.runOnContext(v -> writeResponse(rc, job, t));
            } else {
                writeResponse(rc, job, t);
            }
        });
    }

    private static URI parseRequestUri(RoutingContext rc, String pathPrefix) {
        // Vert.x's request().uri() returns the path + query as written
        // by the client. We reconstruct an absolute-ish URI so Smithy's
        // protocol layer sees scheme/host/port consistently with the
        // Netty path. The path prefix the bridge applied at bind time
        // is stripped here so the protocol's matcher (which uses the
        // model's raw `@http(uri:...)` traits) sees the operation's
        // canonical path.
        var req = rc.request();
        String stripped = stripPrefix(req.uri(), pathPrefix);
        try {
            String scheme = req.isSSL() ? "https" : "http";
            String authority = req.host() == null ? "localhost" : req.host();
            return new URI(scheme + "://" + authority + stripped);
        } catch (URISyntaxException e) {
            return URI.create(stripped);
        }
    }

    private static String stripPrefix(String rawUri, String pathPrefix) {
        if (pathPrefix.isEmpty() || rawUri == null || rawUri.isEmpty()) {
            return rawUri;
        }
        // pathPrefix is normalized to begin with "/" and not end with "/".
        if (rawUri.startsWith(pathPrefix)) {
            String tail = rawUri.substring(pathPrefix.length());
            return tail.isEmpty() ? "/" : tail;
        }
        return rawUri;
    }

    private static void writeResponse(RoutingContext rc, HttpJob job, Throwable failure) {
        var resp = rc.response();
        if (resp.ended()) {
            return;
        }
        if (failure != null) {
            LOG.error("Smithy operation {} failed; returning 500", job.operation().name(), failure);
            if (!resp.headWritten()) {
                resp.setStatusCode(500);
            }
            resp.end();
            return;
        }
        try {
            int statusCode = job.response().getStatusCode();
            if (statusCode <= 0) {
                statusCode = 200;
            }
            resp.setStatusCode(statusCode);
            // Copy framework-set headers onto the Vert.x response.
            for (var entry : job.response().headers().map().entrySet()) {
                String name = entry.getKey();
                for (String value : entry.getValue()) {
                    resp.headers().add(name, value);
                }
            }
            var serialized = job.response().getSerializedValue();
            if (serialized != null) {
                if (serialized.contentType() != null && !resp.headers().contains("content-type")) {
                    resp.putHeader("content-type", serialized.contentType());
                }
                byte[] payloadBytes;
                try (var is = serialized.asInputStream()) {
                    payloadBytes = is.readAllBytes();
                }
                if (!resp.headers().contains("content-length")) {
                    resp.putHeader("content-length", Integer.toString(payloadBytes.length));
                }
                resp.end(Buffer.buffer(payloadBytes));
            } else {
                resp.end();
            }
        } catch (Throwable e) {
            LOG.error("Failed to write Smithy response for operation {}", job.operation().name(), e);
            if (!resp.headWritten()) {
                resp.setStatusCode(500);
                resp.end();
            }
        }
    }
}
