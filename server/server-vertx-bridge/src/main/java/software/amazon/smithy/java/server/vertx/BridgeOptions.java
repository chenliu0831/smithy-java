/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import java.time.Duration;
import java.util.Objects;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Tunable parameters for {@link SmithyServiceBridge}.
 *
 * <p>Three knobs:
 * <ul>
 *   <li>{@link #workerCount()} — orchestrator worker pool size. Default
 *       {@code Runtime.getRuntime().availableProcessors() * 2}.</li>
 *   <li>{@link #pathPrefix()} — prepended to every operation's route at
 *       bind time. Default {@code ""} (no prefix).</li>
 *   <li>{@link #shutdownGrace()} — bound on {@link BoundBridge#shutdown()}.
 *       Default 10 seconds.</li>
 * </ul>
 *
 * <p>This class is intentionally narrow. Adding fields requires an ADR;
 * we want consumers to be able to read the surface in one screen and
 * understand exactly what the bridge does on their behalf.
 */
@SmithyUnstableApi
public final class BridgeOptions {

    private static final BridgeOptions DEFAULTS = builder().build();

    private final int workerCount;
    private final String pathPrefix;
    private final Duration shutdownGrace;

    private BridgeOptions(Builder b) {
        this.workerCount = b.workerCount;
        this.pathPrefix = b.pathPrefix;
        this.shutdownGrace = b.shutdownGrace;
    }

    public static BridgeOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int workerCount() {
        return workerCount;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public Duration shutdownGrace() {
        return shutdownGrace;
    }

    public static final class Builder {
        private int workerCount = Runtime.getRuntime().availableProcessors() * 2;
        private String pathPrefix = "";
        private Duration shutdownGrace = Duration.ofSeconds(10);

        private Builder() {}

        public Builder workerCount(int n) {
            if (n <= 0) {
                throw new IllegalArgumentException("workerCount must be > 0, got " + n);
            }
            this.workerCount = n;
            return this;
        }

        public Builder pathPrefix(String prefix) {
            Objects.requireNonNull(prefix, "pathPrefix");
            // Normalize: a non-empty prefix must start with "/" and must
            // not end with "/". Empty string means "no prefix".
            if (prefix.isEmpty()) {
                this.pathPrefix = "";
                return this;
            }
            String p = prefix.startsWith("/") ? prefix : "/" + prefix;
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            this.pathPrefix = p;
            return this;
        }

        public Builder shutdownGrace(Duration d) {
            Objects.requireNonNull(d, "shutdownGrace");
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("shutdownGrace must be > 0");
            }
            this.shutdownGrace = d;
            return this;
        }

        public BridgeOptions build() {
            return new BridgeOptions(this);
        }
    }
}
