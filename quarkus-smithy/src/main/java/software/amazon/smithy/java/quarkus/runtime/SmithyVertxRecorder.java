/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.quarkus.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.util.TypeLiteral;
import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.java.logging.InternalLogger;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.java.server.vertx.BoundBridge;
import software.amazon.smithy.java.server.vertx.BridgeOptions;
import software.amazon.smithy.java.server.vertx.SmithyServiceBridge;

/**
 * Quarkus {@link Recorder} that mounts every CDI-discovered
 * {@link Service} bean on Quarkus's main Vert.x {@link Router} via
 * {@link SmithyServiceBridge}.
 *
 * <p>This is the runtime-side glue described in ADR-0006. It is small
 * by design: the bridge module owns route enumeration, body buffering,
 * orchestrator construction, and dispatch. The recorder's job is
 * (a) collect {@code Service} beans from Arc, (b) translate
 * {@link SmithyServerConfig} into {@link BridgeOptions}, and (c) wire
 * the {@code BoundBridge} into Quarkus's shutdown sequence so dev mode
 * hot reload removes routes cleanly and graceful shutdown drains the
 * orchestrator.
 *
 * <p>Run-time {@link SmithyServerConfig} is injected via the recorder
 * constructor as a {@link RuntimeValue}; build-step methods cannot
 * consume run-time config directly (Quarkus enforces that distinction).
 */
@Recorder
public class SmithyVertxRecorder {

    private static final InternalLogger LOG = InternalLogger.getLogger(SmithyVertxRecorder.class);

    private final RuntimeValue<SmithyServerConfig> config;

    public SmithyVertxRecorder(RuntimeValue<SmithyServerConfig> config) {
        this.config = config;
    }

    public void mount(
            RuntimeValue<Router> mainRouter,
            ShutdownContext shutdown) {

        // Discover @Produces Service beans via CDI. Multi-Service
        // composition is supported (per ADR-0006 open question 3).
        var instance = Arc.container().select(
                new TypeLiteral<Service>() {},
                Any.Literal.INSTANCE);

        List<Service> services = new ArrayList<>();
        for (Service service : instance) {
            services.add(service);
            LOG.info(
                    "Discovered Smithy Service '{}' with {} operation(s)",
                    service.schema().id(),
                    service.getAllOperations().size());
        }

        if (services.isEmpty()) {
            // Apps that depend on quarkus-smithy purely for codegen
            // produce no `@Produces Service` beans. The bridge
            // requires at least one; short-circuit so the extension
            // is silent in that case. Codegen still ran earlier in
            // the build pipeline.
            //
            // Note: ADR-0006 open question 7 originally proposed a
            // build-time error in this case. We chose runtime no-op
            // so codegen-only consumers don't have to suppress a
            // build check — see ADR-0006 §Open questions.
            LOG.info(
                    "No @Produces Service beans found. Skipping the Vert.x "
                            + "bridge mount; codegen-only apps will not see any "
                            + "Smithy operations on the HTTP router.");
            return;
        }

        SmithyServerConfig cfg = config.getValue();

        // Build the bridge from the user's config.
        var optionsBuilder = BridgeOptions.builder()
                .pathPrefix(cfg.pathPrefix().orElse(""))
                .shutdownGrace(cfg.shutdownGrace());
        cfg.workers().ifPresent(optionsBuilder::workerCount);
        BridgeOptions options = optionsBuilder.build();

        BoundBridge bound = SmithyServiceBridge
                .bridge(services, options)
                .bind(mainRouter.getValue());

        // Hot reload + ordered shutdown. unbind() removes every route
        // the bridge installed in one call; shutdown() drains the
        // orchestrator (best-effort; see BoundBridge javadoc).
        shutdown.addShutdownTask(bound::unbind);
        shutdown.addShutdownTask(() -> {
            try {
                bound.shutdown().get();
            } catch (Exception e) {
                LOG.warn("Error during bridge shutdown", e);
            }
        });

        LOG.info(
                "Smithy operations mounted on Quarkus's main HTTP router "
                        + "(path prefix: '{}', services: {})",
                cfg.pathPrefix().orElse("/"),
                services.size());
    }
}
