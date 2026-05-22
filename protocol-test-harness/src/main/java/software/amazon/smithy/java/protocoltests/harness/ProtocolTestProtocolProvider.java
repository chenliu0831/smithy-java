/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import software.amazon.smithy.java.context.Context;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.java.server.core.Job;
import software.amazon.smithy.java.server.core.ProtocolResolver;
import software.amazon.smithy.java.server.core.RouteSpec;
import software.amazon.smithy.java.server.core.ServerProtocol;
import software.amazon.smithy.java.server.core.ServerProtocolProvider;
import software.amazon.smithy.java.server.core.ServiceProtocolResolutionRequest;
import software.amazon.smithy.java.server.core.ServiceProtocolResolutionResult;
import software.amazon.smithy.model.shapes.ShapeId;

public class ProtocolTestProtocolProvider implements ServerProtocolProvider {

    @Override
    public ServerProtocol provideProtocolHandler(List<Service> candidateServices) {
        return new DelegatingServerProtocol(candidateServices);
    }

    @Override
    public ShapeId getProtocolId() {
        return ShapeId.from("aws.protocols#protocolTestsDelegatingProtocol");
    }

    @Override
    public int precision() {
        return Integer.MAX_VALUE;
    }

    private static class DelegatingServerProtocol extends ServerProtocol {

        private static final Context.Key<ServerProtocol> PROTOCOL_TO_TEST = Context.key("protocol-to-test");

        private final Map<ShapeId, ServerProtocol> delegateProtocols;
        private final List<Service> services;

        public DelegatingServerProtocol(List<Service> candidateServices) {
            super(candidateServices);
            this.services = List.copyOf(candidateServices);
            delegateProtocols = ServiceLoader.load(
                    ServerProtocolProvider.class,
                    ProtocolResolver.class.getClassLoader())
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(p -> p.getClass() != ProtocolTestProtocolProvider.class)
                    .collect(
                            Collectors.toMap(
                                    ServerProtocolProvider::getProtocolId,
                                    s -> s.provideProtocolHandler(candidateServices)));
        }

        @Override
        public ShapeId getProtocolId() {
            return ShapeId.from("");
        }

        /**
         * Mounts a single bridge route at {@code POST /} per registered
         * service so the harness's {@code HttpServerResponseProtocolTestProvider}
         * probes — which target the bare endpoint and identify the
         * operation by {@code x-protocol-test-*} headers — actually
         * reach a Vert.x route. Without this override the bridge
         * installs no route for {@code /}, Vert.x falls through to its
         * default 404 handler, and every server-side response test
         * fails on the harness 404 page rather than the protocol-under-
         * test's actual response.
         *
         * <p>The {@code operation} field on {@link RouteSpec} carries
         * the service's first operation as a placeholder; the real
         * operation is selected per-request by
         * {@link #resolveOperation} from the test headers, and
         * {@code OperationDispatch} uses the resolver's choice instead
         * of the bind-time-pinned placeholder.
         *
         * <p>Pre-ADR-0008 patch. Becomes dead code (and gets deleted)
         * once the bridge moves to a single catch-all route +
         * {@code ProtocolResolver}, since the resolver invokes
         * {@code resolveOperation} for every request regardless of
         * URI.
         */
        @Override
        public List<RouteSpec> enumerateRoutes() {
            // Emit exactly one route covering all candidate services.
            // Multiple RouteSpecs sharing (POST, /) but pointing at
            // different (service, operation) pairs would trip the
            // bridge's bind-time collision detector, even though we
            // semantically claim *all* of them via headers, not just
            // one. The dispatcher uses the resolver's chosen
            // operation (see OperationDispatch.handle) so the
            // placeholder service/operation here is functionally
            // ignored at request time.
            for (Service service : services) {
                var ops = service.getAllOperations();
                if (!ops.isEmpty()) {
                    return List.of(new RouteSpec("POST", "/", service, ops.iterator().next()));
                }
            }
            return List.of();
        }

        @Override
        public ServiceProtocolResolutionResult resolveOperation(
                ServiceProtocolResolutionRequest request,
                List<Service> candidates
        ) {
            String protocolIdHeader = request.headers().firstValue("x-protocol-test-protocol-id");
            if (protocolIdHeader != null) {
                ServerProtocol protocol = delegateProtocols.get(ShapeId.from(protocolIdHeader));
                request.requestContext().put(PROTOCOL_TO_TEST, protocol);
                ShapeId serviceId = ShapeId.from(request.headers().firstValue("x-protocol-test-service"));
                ShapeId operationId = ShapeId.from(request.headers().firstValue("x-protocol-test-operation"));
                for (var service : candidates) {
                    if (service.schema().id().equals(serviceId)) {
                        for (var operation : service.getAllOperations()) {
                            if (operation.getApiOperation().schema().id().equals(operationId)) {
                                return new ServiceProtocolResolutionResult(service, operation, this);
                            }
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public CompletableFuture<Void> deserializeInput(Job job) {
            var protocol = job.request().context().get(PROTOCOL_TO_TEST);
            if (protocol != null) {
                job.request()
                        .setDeserializedValue(
                                job.operation().getApiOperation().inputBuilder().errorCorrection().build());
                return CompletableFuture.completedFuture(null);
            }
            throw new IllegalStateException("Should not be invoked if no protocol was selected");
        }

        @Override
        public CompletableFuture<Void> serializeOutput(Job job, SerializableStruct output, boolean isError) {
            var protocol = job.request().context().get(PROTOCOL_TO_TEST);
            if (protocol != null) {
                if (isError) {
                    return protocol.serializeError(job, (ModeledException) output);
                } else {
                    return protocol.serializeOutput(job, output);
                }
            }
            throw new IllegalStateException("Should not be invoked if no protocol was selected");
        }
    }
}
