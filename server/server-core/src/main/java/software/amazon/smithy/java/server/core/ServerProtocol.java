/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.core.serde.SerializationException;
import software.amazon.smithy.java.framework.model.InternalFailureException;
import software.amazon.smithy.java.framework.model.MalformedRequestException;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.model.shapes.ShapeId;

public abstract class ServerProtocol {

    private final List<Service> services;

    protected ServerProtocol(List<Service> services) {
        this.services = services;
    }

    public abstract ShapeId getProtocolId();

    public abstract ServiceProtocolResolutionResult resolveOperation(
            ServiceProtocolResolutionRequest request,
            List<Service> candidates
    );

    /**
     * Enumerate the per-operation HTTP routes this protocol owns over the
     * services it was constructed with. Used by transports that mount
     * Smithy operations on an externally-managed router (e.g., the
     * Vert.x bridge) rather than dispatching from a single catch-all
     * handler. Default returns an empty list; protocols that participate
     * in router-mountable transports override it.
     *
     * <p>Returned paths follow Vert.x-style placeholder syntax
     * ({@code /order/:id}) so they can be registered directly with a
     * Vert.x {@code Router}. Smithy {@code @http(uri:"/order/{id}")}
     * traits translate to {@code /order/:id}.
     */
    public List<RouteSpec> enumerateRoutes() {
        return List.of();
    }

    public abstract CompletableFuture<Void> deserializeInput(Job job);

    public final CompletableFuture<Void> serializeOutput(Job job, SerializableStruct output) {
        return serializeOutput(job, output, false);
    }

    public final CompletableFuture<Void> serializeError(Job job, Throwable error) {
        return serializeError(
                job,
                error instanceof ModeledException me ? me
                        : translate(error));
    }

    private static ModeledException translate(Throwable error) {
        if (error instanceof SerializationException se) {
            return MalformedRequestException.builder()
                    .withoutStackTrace()
                    .withCause(se)
                    .message(se.getMessage())
                    .build();
        }
        return InternalFailureException.builder().withCause(error).build();
    }

    protected abstract CompletableFuture<Void> serializeOutput(Job job, SerializableStruct output, boolean isError);

    public final CompletableFuture<Void> serializeError(Job job, ModeledException error) {
        // Check both implicit errors and operation errors to see if modeled API exception is
        // defined as part of service interface. Otherwise, throw generic exception.
        if (!job.operation().getOwningService().typeRegistry().contains(error.schema().id())
                && !job.operation().getApiOperation().errorRegistry().contains(error.schema().id())) {
            error = InternalFailureException.builder().withCause(error).build();
        }
        return serializeOutput(job, error, true);
    }
}
