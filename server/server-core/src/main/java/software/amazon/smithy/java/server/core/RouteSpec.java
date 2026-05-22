/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.core;

import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.server.Operation;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * A single ({@link #httpMethod}, {@link #path}) pair that the
 * {@link ServerProtocol} owns and that callers can use to register a
 * dedicated transport route. {@link #operation} is the {@link Operation}
 * the path resolves to; {@link #service} is its owning {@link Service}.
 *
 * <p>Returned from {@link ServerProtocol#enumerateRoutes()}. Path values
 * follow Vert.x route syntax for path parameters (e.g.,
 * {@code /order/:id}); protocols whose model uses Smithy's
 * {@code {label}} syntax should translate to {@code :label} when
 * producing this record.
 */
@SmithyUnstableApi
public record RouteSpec(
        String httpMethod,
        String path,
        Service service,
        Operation<? extends SerializableStruct, ? extends SerializableStruct> operation) {
}
