# quarkus-smithy (experimental)

A Quarkus extension that integrates Smithy-Java codegen — and, optionally,
the `software.amazon.smithy.java.server.Server` runtime — into Quarkus
applications.

> **Status:** experimental. APIs and configuration keys may change without
> notice between releases. Do not use in production.

## What it does

1. **Codegen.** Replaces the standard `smithy-base` Gradle plugin path.
   When `quarkusGenerateCode` runs (as part of `compileJava`,
   `quarkusBuild`, or `quarkusDev`), the extension's `CodeGenProvider`
   discovers `smithy-build.json`, runs `SmithyBuild` with the
   `java-codegen` plugin in-process, and lays generated Java sources
   into `build/classes/java/quarkus-generated-sources/smithy/` where
   Quarkus's compileJava picks them up automatically. The provider is
   **mode-agnostic**: whatever `modes` (`client`, `server`, `types`) the
   user puts in `smithy-build.json` is what's emitted.

2. **Lifecycle.** Calls `Server.start()` on `StartupEvent` and
   `Server.shutdown()` on `ShutdownEvent` for every CDI bean of type
   `software.amazon.smithy.java.server.Server`. Apps that don't produce a
   `Server` bean (Typed-client, Types-only) get a silent no-op — codegen
   still runs.

## Programming models

Three patterns are supported today. They differ only in what `modes` the
user puts in `smithy-build.json` and which Smithy-Java runtime modules
the user pulls in. The extension itself is the same in every case.

| Model         | `modes`     | Runtime deps (in addition to `quarkus-smithy`)            | Example                                |
| ------------- | ----------- | --------------------------------------------------------- | -------------------------------------- |
| Server-bean   | `["server"]` | `server-netty`, `aws-server-restjson` (or another protocol) | [`examples/quarkus-server`](../examples/quarkus-server/) |
| Typed-client  | `["client"]` | `client-core`, `aws-client-restjson` (or another protocol)  | [`examples/quarkus-client`](../examples/quarkus-client/) |
| Types-only    | `["types"]`  | `core`, plus a codec module if you need serdes              | [`examples/quarkus-types`](../examples/quarkus-types/)   |

See [`CONTEXT.md`](./CONTEXT.md) for the full glossary, including the
deferred External-dispatch and Annotation-discovery models that are named
but not implemented today.

### Server-bean (the original example)

`smithy-build.json`:

```json
{
  "version": "1.0",
  "plugins": {
    "java-codegen": {
      "service": "com.example#CoffeeShop",
      "namespace": "com.example",
      "modes": ["server"]
    }
  }
}
```

User code:

```java
@ApplicationScoped
class CoffeeShopServerConfig {
    @Produces @Singleton
    Server server() {
        return Server.builder()
                .endpoints(URI.create("http://localhost:8888"))
                .addService(CoffeeShop.builder()
                        .addCreateOrderOperation(new CreateOrder())
                        .addGetMenuOperation(new GetMenu())
                        .addGetOrderOperation(new GetOrder())
                        .build())
                .build();
    }
}
```

The producer body is byte-identical to upstream's `BasicServerExample.run()`.

### Typed-client

`smithy-build.json` uses `modes: ["client"]`. The user exposes the
generated client as a CDI bean:

```java
@ApplicationScoped
class CoffeeShopClientProducer {
    @ConfigProperty(name = "coffee-shop.upstream-endpoint",
                    defaultValue = "http://localhost:8888")
    String upstream;

    @Produces @Singleton
    CoffeeShopClient client() {
        return CoffeeShopClient.builder()
                .endpointResolver(EndpointResolver.staticHost(upstream))
                .build();
    }
}
```

No `Server` bean is created. The lifecycle bean stays idle.

### Types-only

`smithy-build.json` uses `modes: ["types"]` with a `selector` (or
explicit `shapes` list) that scopes which shapes get emitted:

```json
{
  "version": "1.0",
  "plugins": {
    "java-codegen": {
      "namespace": "com.example",
      "modes": ["types"],
      "selector": "[id|namespace = 'com.example']"
    }
  }
}
```

The user dispatches the generated POJOs themselves — typically through a
JAX-RS resource, a Vert.x route, or any other Quarkus surface — using
Smithy's JSON codec or any other serializer.

If your `.smithy` model uses AWS traits (`@restJson1`, `@arn`, `@sigv4`,
…) and you don't already have a Smithy-Java protocol runtime on your
classpath (`aws-server-restjson`, `aws-client-restjson`, …), add the
trait jar(s) yourself:

```kotlin
implementation("software.amazon.smithy:smithy-aws-traits:<version>")
```

The extension does not bundle trait jars — see
[ADR-0004](../../docs/adr/0004-limit-deployment-bundling-scope.md).

## Running

```console
./gradlew :examples:quarkus-server:quarkusDev   # Server-bean
./gradlew :examples:quarkus-client:quarkusDev   # Typed-client
./gradlew :examples:quarkus-types:quarkusDev    # Types-only
```

Edit a `.smithy` file, an operation impl, or a route handler — Quarkus
reloads.

## Modules

- `runtime/` — runtime classpath, ships with the application. Contains
  `SmithyServerLifecycle` (the `@Observes` lifecycle bean).
- `deployment/` — deployment classpath, build-time only. Contains
  `SmithyCodeGenProvider` and `SmithyProcessor` (the Quarkus
  `@BuildStep`s). Bundles `software.amazon.smithy:smithy-build` and
  `software.amazon.smithy.java:codegen-plugin` so the user does not
  need to declare them.
- `integration-tests/` — exercises `SmithyCodeGenProvider` end-to-end for
  each codegen mode (server, client, types) and the no-`smithy-build.json`
  short-circuit.

## Why "experimental"

- Depends on `JavaCodegenPlugin`'s settings shape, which is
  `@SmithyInternalApi`. A smithy-java release could break this
  extension without notice.
- Only the `source` Smithy projection is run.
- Only the `java-codegen` plugin block is honored.
- Native-image support is out of scope for the first cut.

See `/docs/adr/` at the workspace root for the design rationale —
[0001](../../docs/adr/0001-produces-server-not-annotation-discovery.md),
[0002](../../docs/adr/0002-bundle-codegen-in-deployment-artifact.md),
[0003](../../docs/adr/0003-defer-shared-transport-and-interceptor-spi.md),
[0004](../../docs/adr/0004-limit-deployment-bundling-scope.md).
