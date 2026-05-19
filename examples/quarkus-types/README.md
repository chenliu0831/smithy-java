## Example: Quarkus Types-only

A Quarkus application that uses Smithy purely as a source of truth for data
shapes — no Smithy `Server`, no Smithy `Client`. Demonstrates the
**Types-only** programming model of the experimental
[`quarkus-smithy` extension](../../quarkus-smithy/README.md).

The app generates `Menu` / `MenuItem` from `src/main/smithy/menu.smithy`,
mounts a couple of Vert.x routes on Quarkus's HTTP server, and uses
Smithy-Java's `JsonCodec` for serdes.

```java
@ApplicationScoped
public class MenuRoute {
    private static final JsonCodec CODEC = JsonCodec.builder()
            .useJsonName(true)
            .useTimestampFormat(true)
            .build();

    void registerRoutes(@Observes StartupEvent ev, Router router) {
        router.get("/menu")
                .produces("application/json")
                .handler(rc -> rc.response().end(Buffer.buffer(CODEC.serializeToString(MENU))));
    }
}
```

### Project layout

```
.
├── build.gradle.kts                  ← apply io.quarkus, depend on quarkus-smithy
├── settings.gradle.kts
├── gradle.properties
├── smithy-build.json                 ← project root, configures java-codegen with modes:["types"]
├── src/main/smithy/
│   └── menu.smithy
├── src/main/java/.../MenuRoute.java
└── src/main/resources/application.properties
```

### Why "Types-only"

The `smithy-build.json` declares only `modes: ["types"]`. Codegen emits the
`Menu` / `MenuItem` records under `model/`, with no `*Operation`, no
`*Client`, and no service stub. The runtime classpath needs only
`software.amazon.smithy.java:core` (transitive via the codec) — neither
`server-api` nor `client-core` are pulled in.

The extension's `SmithyServerLifecycle` bean still exists in this app, but
its `Instance<Server>` resolves to nothing, so its startup and shutdown
observers do nothing. That is the intended behavior in this model.

### Running

```console
gradle :examples:quarkus-types:quarkusDev
```

### Curl probes

```console
curl http://localhost:8080/menu

curl -X POST http://localhost:8080/menu/echo \
     -H 'content-type: application/json' \
     -d '{"items":[{"name":"Cold Brew","priceCents":425}]}'
```
