/*
 * Example file license header.
 * File header line two
 */

package software.amazon.smithy.java.example.quarkus.client;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.smithy.java.example.quarkus.client.client.CoffeeShopClient;
import software.amazon.smithy.java.example.quarkus.client.model.CreateOrderInput;
import software.amazon.smithy.java.example.quarkus.client.model.GetMenuInput;
import software.amazon.smithy.java.example.quarkus.client.model.GetOrderInput;
import software.amazon.smithy.java.json.JsonCodec;

/**
 * A small Quarkus-side proxy that exposes the upstream coffee-shop API on
 * Quarkus's HTTP port (8080), translating each request into a typed
 * {@link CoffeeShopClient} call.
 *
 * <p>Demonstrates the Typed-client model's main payoff: the user gets a
 * type-safe RPC client (no JSON-by-hand, no REST-template glue) generated
 * from the same {@code .smithy} model that defines the upstream service.
 */
@ApplicationScoped
public class CoffeeProxyRoute {

    private static final JsonCodec CODEC = JsonCodec.builder()
            .useJsonName(true)
            .useTimestampFormat(true)
            .build();

    @Inject
    CoffeeShopClient coffee;

    void registerRoutes(@Observes StartupEvent ev, Router router) {
        router.get("/menu")
                .produces("application/json")
                .handler(rc -> {
                    var output = coffee.getMenu(GetMenuInput.builder().build());
                    rc.response().end(Buffer.buffer(CODEC.serializeToString(output)));
                });

        router.put("/order")
                .consumes("application/json")
                .produces("application/json")
                // Vert.x doesn't buffer request bodies unless a BodyHandler
                // sits in front of the route — quarkus-vertx-http does not
                // install one by default.
                .handler(BodyHandler.create())
                .handler(rc -> {
                    var input = CODEC.deserializeShape(rc.body().buffer().getBytes(), CreateOrderInput.builder());
                    var output = coffee.createOrder(input);
                    rc.response().end(Buffer.buffer(CODEC.serializeToString(output)));
                });

        router.get("/order/:id")
                .produces("application/json")
                .handler(rc -> {
                    var input = GetOrderInput.builder().id(rc.pathParam("id")).build();
                    var output = coffee.getOrder(input);
                    rc.response().end(Buffer.buffer(CODEC.serializeToString(output)));
                });
    }
}
