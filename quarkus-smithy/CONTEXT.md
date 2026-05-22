# Quarkus Smithy Extension

The Quarkus extension that integrates Smithy-Java codegen and the
Vert.x-bridge-mounted server runtime into Quarkus applications.
Terminology here disambiguates concepts that appear under the same
English word inside a single Quarkus JVM.

## Language

### Servers and listeners

**Quarkus HTTP server**:
The Vert.x-based HTTP server provided by `quarkus-vertx-http`. Hosts
the user's Smithy operations (mounted by this extension), plus
`/q/dev`, `/q/health`, REST endpoints, etc. Smithy operations share
this server's port (per ADR-0003).

**Vert.x bridge**:
The upstream module `:server:server-vertx-bridge` that this extension
consumes. Walks each `Service` bean's operations, derives the route
from the protocol (restJson1 `@http(method, uri)` or rpcv2
`POST /service/<Name>/operation/<Op>`), and registers each as a
typed Vert.x route on Quarkus's `Router`. See ADR-0006 for the
public API (`SmithyServiceBridge`, `BoundBridge`, `BridgeOptions`).

**Smithy server (deprecated)**:
The previous architecture, before ADR-0003. Was a separate
`software.amazon.smithy.java.server.Server` (Netty) instance owning
its own listener and port. No longer present in `quarkus-smithy`.
_Avoid_: this term referring to anything in the current architecture.

### Programming model

The extension is a **server extension**. The supported user-facing
programming model is the Service-bean model.

**Service-bean model** (supported, the canonical pattern):
The user supplies a CDI producer method that returns a built
`software.amazon.smithy.java.server.Service` (the generated service
stub from `modes: ["server"]` codegen output). The extension
discovers every `@Produces Service` bean and mounts each operation
on Quarkus's main HTTP router via the upstream
`:server:server-vertx-bridge` module. There is **no separate Smithy
listener**; operations share the Quarkus HTTP server's port.
_Replaces (per ADR-0003 + ADR-0006)_: "Server-bean model", "@Produces
Server" model.
_Avoid_: "manual server", "explicit server", "two-port mode".

**Annotation-discovery model** (named, deferred):
The hypothetical future programming model in which a CDI annotation
(e.g. `@SmithyService`) marks operation implementations and the
extension builds the `Service` itself, analogous to `quarkus-grpc`'s
`@GrpcService`. Not implemented; called out so that "the model we did
not pick" has a name.

**Typed-client model** (future direction, not shipped):
Generate a Smithy client (`modes: ["client"]`) and expose it as a CDI
bean. The `CodeGenProvider` is mode-agnostic and will emit client
code on demand, but the extension does not surface a documented
runtime path for this pattern. The recorder's no-Service
short-circuit is the only current accommodation.
_Avoid_: treating this as a supported programming model; it is a
forward-looking direction only.

**Types-only model** (future direction, not shipped):
Generate Smithy POJOs (`modes: ["types"]`) and use them as you like.
Same status as Typed-client — codegen runs, no runtime story is
shipped.
_Avoid_: treating this as a supported programming model.

### Smithy concepts (as used inside this extension)

**Smithy `Service`**:
The generated service stub class corresponding to a `service` shape in
the user's `.smithy` model. The user attaches operation implementations
to it via its builder (`CoffeeShop.builder().addCreateOrderOperation(...).build()`).
The extension's recorder discovers `Service` instances via
`Instance<Service>` and hands them to the Vert.x bridge.

**Smithy operation**:
A generated interface for a single RPC, implemented by the user. The
implementation class is referenced by name in the
`<ServiceName>.builder().add<OperationName>Operation(...)` chain inside
the user's `@Produces Service` method.

**`java-codegen` plugin**:
The Smithy build plugin (from `:codegen:codegen-plugin`) that turns
`.smithy` shapes into Java source. The extension only honors this plugin
inside `smithy-build.json`; `smithy-base` Gradle plugin wiring is
intentionally not used. The plugin is **mode-agnostic**: whatever
`modes` (`server`, `client`, `types`) the user puts in
`smithy-build.json` is what's emitted.

## Relationships

- A Quarkus application has zero or more `@Produces Service` beans and
  exactly one Quarkus HTTP server.
- The `SmithyVertxRecorder` runs at `RUNTIME_INIT`, walks
  `Instance<Service>`, and either mounts the bridge (one or more
  Service beans) or short-circuits with an INFO log (zero beans —
  e.g. an app that depends on `quarkus-smithy` only for codegen).
- The `Annotation-discovery model` is named to mark a boundary, not
  because it exists.

## Example dialogue

> **Dev:** "I have a Smithy service and want it served by my Quarkus
> app. What do I produce?"
> **Domain expert:** "A `@Produces Service` bean. The extension mounts
> every operation on Quarkus's HTTP server automatically — no separate
> port, no `Server.builder()`. See `examples/quarkus-server/`."

> **Dev:** "Can I have two Smithy services in the same Quarkus app?"
> **Domain expert:** "Yes — produce two `@Produces Service` beans. The
> bridge composes them on the same router; `@http` collisions across
> services fail-fast at bind time. See ADR-0006."

> **Dev:** "Where's the URL of my Smithy server?"
> **Domain expert:** "There isn't a separate one. Smithy operations
> live on Quarkus's HTTP server — `quarkus.http.host`/`quarkus.http.port`
> control the listener. To put Smithy operations under a sub-tree, set
> `quarkus.smithy.server.path-prefix=/api/smithy`."

## Flagged ambiguities

- "Server" used to mean both the (now-removed) Smithy Netty listener
  and the Quarkus HTTP server. Resolution: there is only one server
  now (Quarkus HTTP), and we don't say "Smithy server" anymore.
- "Service" can mean a Smithy `service` shape, a Smithy `Service`
  generated stub, or a CDI `@ApplicationScoped` bean. Resolution: we
  use "Service" only for the generated stub (the type returned by the
  user's `@Produces` method); Smithy `service` shape is the model-side
  noun; CDI services are referred to as "beans".
- "@Produces Server" was the user-facing producer pattern in earlier
  experimental releases. ADR-0003 and ADR-0006 superseded it with
  `@Produces Service`. Old references in ADR-0001 are preserved for
  historical accuracy; new prose uses the new name.
