## Example: Quarkus Typed-client

A Quarkus application that consumes an upstream Smithy service via a
generated, type-safe Smithy client. Demonstrates the **Typed-client**
programming model of the experimental
[`quarkus-smithy` extension](../../quarkus-smithy/README.md).

The same `coffee.smithy` model that defines the upstream
[`examples/quarkus-server/`](../quarkus-server/README.md) is fed through
the extension's codegen with `modes: ["client"]`, producing a
`CoffeeShopClient` that the application exposes via a CDI producer:

```java
@ApplicationScoped
public class CoffeeShopClientProducer {

    @ConfigProperty(name = "coffee-shop.upstream-endpoint",
                    defaultValue = "http://localhost:8888")
    String upstreamEndpoint;

    @Produces
    @Singleton
    CoffeeShopClient client() {
        return CoffeeShopClient.builder()
                .endpointResolver(EndpointResolver.staticHost(upstreamEndpoint))
                .build();
    }
}
```

A small Vert.x route handler injects the client and exposes the upstream
operations on Quarkus's HTTP port (8080) — handy for inspecting the
typed-client path with `curl`.

### Project layout

```
.
├── build.gradle.kts                  ← apply io.quarkus, depend on quarkus-smithy
├── settings.gradle.kts
├── gradle.properties
├── smithy-build.json                 ← project root, configures java-codegen with modes:["client"]
├── src/main/smithy/
│   ├── coffee.smithy
│   ├── main.smithy
│   └── order.smithy
├── src/main/java/.../CoffeeShopClientProducer.java
├── src/main/java/.../CoffeeProxyRoute.java
└── src/main/resources/application.properties
```

### Why "Typed-client"

The `smithy-build.json` declares only `modes: ["client"]`. Codegen emits
`CoffeeShopClient` plus the model classes (`CreateOrderInput`,
`GetMenuOutput`, …). No `*Operation` interfaces, no `Service` stub, no
server-side scaffolding.

The runtime classpath does **not** include `server-netty` or
`aws-server-restjson`. The extension's `SmithyServerLifecycle` bean still
exists in this app, but its `Instance<Server>` resolves to nothing, so its
startup and shutdown observers do nothing — the intended behavior in this
model.

### Running

You'll want two JVMs side by side. Both apps default Quarkus's HTTP server
to :8080, so override the client's port to avoid a collision:

```console
gradle :examples:quarkus-server:quarkusDev                             # upstream on :8888 (Smithy) + :8080 (Quarkus)
gradle :examples:quarkus-client:quarkusDev -Dquarkus.http.port=8081    # this app, proxy on :8081
```

### Curl probes

```console
curl http://localhost:8081/menu

curl -X PUT http://localhost:8081/order \
     -H 'content-type: application/json' \
     -d '{"coffeeType":"DRIP"}'

curl http://localhost:8081/order/<id>
```

Pointing the client at a different upstream is just a runtime override:

```console
gradle :examples:quarkus-client:quarkusDev \
       -Dcoffee-shop.upstream-endpoint=https://my-coffee.example.com
```
