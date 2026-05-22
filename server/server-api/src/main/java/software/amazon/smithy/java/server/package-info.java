/**
 * Server API types and interfaces.
 *
 * <p>{@link software.amazon.smithy.java.server.Service} and
 * {@link software.amazon.smithy.java.server.Operation} are stable
 * (consumed by user code via {@code @Produces} in {@code quarkus-smithy}
 * and by generated service stubs).
 *
 * <p>Other types in this package — {@link
 * software.amazon.smithy.java.server.Server},
 * {@link software.amazon.smithy.java.server.ServerBuilder},
 * {@link software.amazon.smithy.java.server.ServerProvider},
 * {@link software.amazon.smithy.java.server.RequestContext},
 * {@link software.amazon.smithy.java.server.OperationFilters},
 * {@link software.amazon.smithy.java.server.Route} — are unstable
 * pending the work tracked in ADR-0005 (server-side interceptor SPI),
 * which expects to enrich {@code RequestContext} with header access
 * and typed attribute storage.
 */
@SmithyUnstableApi
package software.amazon.smithy.java.server;

import software.amazon.smithy.utils.SmithyUnstableApi;
