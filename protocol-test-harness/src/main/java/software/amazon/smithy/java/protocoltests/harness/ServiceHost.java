/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness;

import java.net.URI;
import software.amazon.smithy.java.server.Service;

/**
 * Hosts a {@link Service} for the duration of a protocol-test run.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}.
 * The harness selects one by name at startup using the
 * {@code -Dsmithy.protocoltest.host=<name>} system property; default is
 * {@code "netty"}.
 *
 * <p>Lifecycle: {@link #start(Service)} is called once per
 * {@link ProtocolTest}-annotated class (in {@code @BeforeAll}); the
 * returned URI is the endpoint protocol tests dispatch HTTP requests
 * against. {@link #stop()} runs in {@code @AfterAll}; impls must be
 * idempotent.
 *
 * <p>This SPI is the seam ADR-0007 introduces so the existing
 * server-side protocol-compliance test classes can run unchanged
 * against multiple transports (Netty today; the Vert.x bridge and
 * the Quarkus extension via the same mechanism).
 */
public interface ServiceHost {

    /**
     * Identifier used by the system-property registry. Must be
     * stable across releases; matched against
     * {@code -Dsmithy.protocoltest.host=<name>}.
     */
    String name();

    /**
     * Boot the host with the supplied Service mounted. Must return
     * only after the host is accepting connections at the returned
     * URI.
     */
    URI start(Service service);

    /** Idempotent shutdown. */
    void stop();
}
