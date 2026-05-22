## Example: Quarkus Server

A Smithy-Java service running inside a Quarkus application via the
[`quarkus-smithy` extension](../../quarkus-smithy/README.md).

The user produces a `@Produces Service` bean (the generated `CoffeeShop`
stub) and the extension mounts every operation on Quarkus's main HTTP
router via the upstream `:server:server-vertx-bridge` module. Smithy
operations share the Quarkus HTTP server's port — no separate Smithy
listener.

```java
@ApplicationScoped
public class CoffeeShopServerConfig {

    @Produces
    @Singleton
    Service coffeeShop() {
        return CoffeeShop.builder()
                .addCreateOrderOperation(new CreateOrder())
                .addGetMenuOperation(new GetMenu())
                .addGetOrderOperation(new GetOrder())
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

## Dev & Test Guide

This is the end-to-end playbook for booting, exercising, hot-reloading,
packaging, and integration-testing this example. Everything below assumes
you're running from `smithy-java/examples/quarkus-server/` unless noted.

### 0. One-time prerequisites

This example is a **standalone Gradle build**. It is intentionally not
included in `smithy-java`'s root `settings.gradle.kts`, because Quarkus
dev-mode workspace discovery would otherwise substitute sibling
smithy-java projects' raw `build/classes` directories for their published
jars — bypassing `:codecs:json-codec`'s shadowJar (which relocates
Jackson 3) and splitting the classloader graph in ways that break the
`SchemaExtensionProvider` SPI lookup. Running standalone makes the example
behave exactly the way a real customer's project would.

So, before the first run (and any time you change smithy-java sources),
publish smithy-java to your local Maven repo:

```console
# from smithy-java/
./gradlew publishToMavenLocal
```

### 1. Boot the dev server

```console
# from smithy-java/examples/quarkus-server/
./gradlew quarkusDev
```

The server listens on `http://localhost:8080`. Watch the boot log for the
bridge's `Mounted Smithy operation …` lines — one per operation. They
confirm every operation reached the Vert.x router.

### 2. Curl the operations

```console
curl http://localhost:8080/menu
curl -X PUT http://localhost:8080/order -H 'Content-Type: application/json' \
     -d '{"coffeeType":"LATTE"}'
curl http://localhost:8080/order/<id-from-PUT-response>
```

Smithy operations share Quarkus's HTTP port, so the standard Quarkus
endpoints still work alongside them:

```console
curl http://localhost:8080/q/health   # served by Quarkus, not by Smithy
```

### 3. Hot reload

While `quarkusDev` is running:

- Edit `CreateOrder.java` (e.g., change a status string), save → re-curl
  `PUT /order`. The response reflects the change without a restart.
- Edit `src/main/smithy/coffee.smithy` (e.g., add a member), save → the
  `CodeGenProvider` regenerates the stub and the bridge's
  `BoundBridge.unbind()` cleanly removes the previous routes before the
  new ones are mounted.

### 4. Path-prefix mode

To put Smithy operations under `/api/smithy/...` (so REST endpoints can
own the root), set in `src/main/resources/application.properties`:

```properties
quarkus.smithy.server.path-prefix=/api/smithy
```

`@http(uri:"/menu")` then becomes reachable at `/api/smithy/menu`. Verify:

```console
curl -i http://localhost:8080/api/smithy/menu   # 200
curl -i http://localhost:8080/menu              # 404
```

In dev mode you can also live-edit this from the Dev UI Configuration
tile — see step 6.

### 5. Packaged jar (prod profile)

```console
./gradlew quarkusBuild
java -jar build/quarkus-app/quarkus-run.jar
```

Run the same curl probes from step 2 against this — they should all 200,
boot is sub-2s.

### 6. Dev UI

While `quarkusDev` is running, open `http://localhost:8080/q/dev-ui`.

There is no Smithy-specific Dev UI card today (none of the extension's
build steps emit a `CardPageBuildItem`), so use the standard tiles:

- **Endpoints** — confirms every Smithy operation route the bridge
  installed (`GET /menu`, `PUT /order`, `GET /order/:id`, …) alongside
  Quarkus's own routes.
- **Configuration** — search for `quarkus.smithy.server` to live-edit
  `path-prefix`, `workers`, and `shutdown-grace`.
- **ArC** — confirms the `@Produces Service` bean is present and
  unremovable (the extension marks it via `UnremovableBeanBuildItem`).
- **Build Steps** — confirms `SmithyProcessor` ran and which build
  items it produced.
- **Continuous Testing** — press `r` in the dev terminal (or open the
  tile) to re-run tests on save.

### 7. Run the extension's own integration tests

These live in the parent smithy-java build, not in this example:

```console
# from smithy-java/
./gradlew :server:server-vertx-bridge:test          # bridge unit tests
./gradlew :quarkus-smithy-integration-tests:test    # extension integ
./gradlew :aws:server:aws-server-restjson:integ     # protocol integ
```
