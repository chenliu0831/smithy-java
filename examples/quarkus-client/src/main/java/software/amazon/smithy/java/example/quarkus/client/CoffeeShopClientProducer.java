/*
 * Example file license header.
 * File header line two
 */

package software.amazon.smithy.java.example.quarkus.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.smithy.java.endpoints.EndpointResolver;
import software.amazon.smithy.java.example.quarkus.client.client.CoffeeShopClient;

/**
 * Exposes the generated {@link CoffeeShopClient} as a CDI bean. The endpoint
 * is read from {@code coffee-shop.upstream-endpoint} (default
 * {@code http://localhost:8888}, which matches the sibling
 * {@code examples/quarkus-server/}).
 *
 * <p>This is the user-facing wiring for the Typed-client programming model:
 * the producer body looks like the upstream
 * {@code BasicClientExample} usage, and the only Quarkus-specific surface is
 * the {@code @Produces} method itself.
 *
 * <p>No {@code Server} bean is produced anywhere in this example, so the
 * extension's {@code SmithyServerLifecycle} resolves zero servers and stays
 * idle through startup and shutdown.
 */
@ApplicationScoped
public class CoffeeShopClientProducer {

    @ConfigProperty(name = "coffee-shop.upstream-endpoint", defaultValue = "http://localhost:8888")
    String upstreamEndpoint;

    @Produces
    @Singleton
    CoffeeShopClient client() {
        return CoffeeShopClient.builder()
                .endpointResolver(EndpointResolver.staticHost(upstreamEndpoint))
                .build();
    }
}
