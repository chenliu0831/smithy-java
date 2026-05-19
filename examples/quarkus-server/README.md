## Example: Quarkus Server

A Smithy-Java server running inside a Quarkus application via the experimental
[`quarkus-smithy` extension](../../quarkus-smithy/README.md).

The user-facing producer method body is byte-identical to
`examples/end-to-end/.../BasicServerExample.run()`. The only Quarkus-specific
surface is the `@Produces Server` wrapper — the extension handles codegen and
lifecycle.

```java
@ApplicationScoped
public class CoffeeShopServerConfig {

    @Produces
    @Singleton
    Server server() {
        return Server.builder()
                .endpoints(URI.create("http://localhost:8888"))
                .addService(
                        CoffeeShop.builder()
                                .addCreateOrderOperation(new CreateOrder())
                                .addGetMenuOperation(new GetMenu())
                                .addGetOrderOperation(new GetOrder())
                                .build())
                .build();
    }
}
```

### Project layout

```
.
├── build.gradle.kts                  ← apply io.quarkus, depend on quarkus-smithy
├── settings.gradle.kts
├── gradle.properties
├── smithy-build.json                 ← project root, configures java-codegen
├── src/main/smithy/                  ← .smithy models (Quarkus convention)
│   ├── coffee.smithy
│   ├── main.smithy
│   └── order.smithy
├── src/main/java/.../CoffeeShopServerConfig.java
├── src/main/java/.../CreateOrder.java
├── src/main/java/.../GetMenu.java
├── src/main/java/.../GetOrder.java
└── src/main/resources/application.properties
```

No `afterEvaluate { ... srcDir(...) }` wiring. No `compileJava.dependsOn(smithyBuild)`.
The `quarkus-smithy` extension's `CodeGenProvider` runs as part of
`quarkusGenerateCode`, generates Java sources directly into Quarkus's
`build/classes/java/quarkus-generated-sources/smithy/` output directory, and
`compileJava` picks them up automatically.

### Running

```console
gradle :examples:quarkus-server:quarkusDev
```

The server listens on `http://localhost:8888`. Edit a `.smithy` file or any
operation impl and Quarkus reloads.

### Curl probes

```console
curl http://localhost:8888/menu
curl -X PUT http://localhost:8888/order -d '{"coffeeType":"DRIP"}'
curl http://localhost:8888/order/<id>
```
