/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.quarkus.runtime;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import software.amazon.smithy.java.logging.InternalLogger;
import software.amazon.smithy.java.server.Server;

/**
 * Manages the lifecycle of every Smithy-Java {@link Server} bean discovered
 * by Arc.
 *
 * <p>On {@link StartupEvent} every {@code Server} is started.
 * {@code Server.start()} is synchronous (blocks until ports are bound), so
 * a startup failure aborts Quarkus boot loud and clear.
 *
 * <p>On {@link ShutdownEvent} every server is shut down. {@code shutdown()}
 * returns a {@link java.util.concurrent.CompletableFuture} which we wait on
 * so Quarkus does not consider the application stopped until the listener
 * has actually drained.
 */
@ApplicationScoped
public class SmithyServerLifecycle {

    private static final InternalLogger LOGGER = InternalLogger.getLogger(SmithyServerLifecycle.class);

    @Inject
    Instance<Server> servers;

    void onStart(@Observes StartupEvent event) {
        for (Server server : servers) {
            LOGGER.info("Starting Smithy server {}", server);
            server.start();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        for (Server server : servers) {
            try {
                LOGGER.info("Shutting down Smithy server {}", server);
                server.shutdown().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while shutting down Smithy server {}", server, e);
            } catch (Exception e) {
                LOGGER.warn("Error while shutting down Smithy server {}", server, e);
            }
        }
    }
}
