/*
 * Example file license header.
 * File header line two
 */

package software.amazon.smithy.java.example.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import software.amazon.smithy.java.example.quarkus.service.CoffeeShop;
import software.amazon.smithy.java.server.Server;

/**
 * The user-facing wiring for a Smithy-Java server inside Quarkus.
 *
 * <p>The {@code server()} method body is byte-identical to
 * {@code examples/end-to-end/.../BasicServerExample.run()}. The only
 * Quarkus-specific surface is the {@link Produces} producer wrapper.
 */
@ApplicationScoped
public class CoffeeShopServerConfig {

    static final URI ENDPOINT = URI.create("http://localhost:8888");

    @Produces
    @Singleton
    Server server() {
        return Server.builder()
                .endpoints(ENDPOINT)
                .addService(
                        CoffeeShop.builder()
                                .addCreateOrderOperation(new CreateOrder())
                                .addGetMenuOperation(new GetMenu())
                                .addGetOrderOperation(new GetOrder())
                                .build())
                .build();
    }
}
