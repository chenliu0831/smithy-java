/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Thrown by {@link SmithyServiceBridge#bind} when two operations declare
 * the same {@code (HTTP method, path)} pair. Model-author error: the
 * collision is unresolvable by Smithy's protocol-aware dispatch and
 * has to be fixed in the {@code .smithy} sources.
 */
@SmithyUnstableApi
public final class BindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BindException(String message) {
        super(message);
    }
}
