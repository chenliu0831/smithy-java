/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness;

import java.net.URI;
import software.amazon.smithy.java.server.Server;
import software.amazon.smithy.java.server.Service;

/**
 * Default {@link ServiceHost} — boots Smithy-Java's Netty server
 * ({@code :server:server-netty}) listening on a free local port.
 *
 * <p>Selected when {@code -Dsmithy.protocoltest.host} is unset or
 * equals {@code "netty"}. Preserves the harness's pre-ADR-0007
 * behavior where {@code Server.builder()} was called inline inside
 * {@code ProtocolTestExtension.beforeAll}.
 */
public final class NettyServiceHost implements ServiceHost {

    private Server server;

    @Override
    public String name() {
        return "netty";
    }

    @Override
    public URI start(Service service) {
        java.util.Objects.requireNonNull(service, "service");
        if (server != null) {
            throw new IllegalStateException(
                    "NettyServiceHost.start() called twice without an intervening stop(); "
                            + "would leak the previously-started Server.");
        }
        URI endpoint = URI.create("http://localhost:" + Ports.free());
        this.server = Server.builder()
                .endpoints(endpoint)
                .addService(service)
                .build();
        server.start();
        return endpoint;
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        try {
            server.shutdown().get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            server = null;
        }
    }
}
