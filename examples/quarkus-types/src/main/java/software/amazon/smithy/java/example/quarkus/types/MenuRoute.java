/*
 * Example file license header.
 * File header line two
 */

package software.amazon.smithy.java.example.quarkus.types;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import software.amazon.smithy.java.example.quarkus.types.model.Menu;
import software.amazon.smithy.java.example.quarkus.types.model.MenuItem;
import software.amazon.smithy.java.json.JsonCodec;

/**
 * Demonstrates the Types-only programming model: Smithy is the source of
 * truth for the {@link Menu} / {@link MenuItem} shapes, but dispatch and
 * transport are entirely the Quarkus app's concern. No
 * {@code software.amazon.smithy.java.server.Server} bean is produced — the
 * extension's lifecycle observer no-ops at startup and shutdown.
 *
 * <p>Routes are mounted on Quarkus's Vert.x {@link Router} on startup. The
 * wire format is JSON, encoded by Smithy-Java's {@link JsonCodec}. We use
 * the Smithy codec rather than Jackson here to show the boundary clearly —
 * Smithy traits like {@code @jsonName} and {@code @timestampFormat} need
 * the Smithy codec to round-trip faithfully.
 */
@ApplicationScoped
public class MenuRoute {

    private static final JsonCodec CODEC = JsonCodec.builder()
            .useJsonName(true)
            .useTimestampFormat(true)
            .build();

    private static final Menu MENU = Menu.builder()
            .items(List.of(
                    MenuItem.builder()
                            .name("Drip")
                            .priceCents(300)
                            .description("Clean-bodied, mellow notes; less concentrated than espresso.")
                            .build(),
                    MenuItem.builder()
                            .name("Latte")
                            .priceCents(450)
                            .description("Espresso with steamed milk, smooth and creamy.")
                            .build(),
                    MenuItem.builder()
                            .name("Espresso")
                            .priceCents(350)
                            .description("Highly concentrated, syrupy, full-bodied.")
                            .build()))
            .build();

    void registerRoutes(@Observes StartupEvent ev, Router router) {
        router.get("/menu")
                .produces("application/json")
                .handler(rc -> rc.response().end(Buffer.buffer(CODEC.serializeToString(MENU))));

        router.post("/menu/echo")
                .consumes("application/json")
                .produces("application/json")
                // Vert.x doesn't buffer request bodies unless a BodyHandler
                // sits in front of the route — quarkus-vertx-http does not
                // install one by default.
                .handler(BodyHandler.create())
                .handler(rc -> {
                    Menu received = CODEC.deserializeShape(rc.body().buffer().getBytes(), Menu.builder());
                    rc.response().end(Buffer.buffer(CODEC.serializeToString(received)));
                });
    }
}
