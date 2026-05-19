# Quarkus Smithy Extension

The experimental extension that integrates Smithy-Java code generation and the
`software.amazon.smithy.java.server.Server` runtime into Quarkus applications.
Terminology here disambiguates concepts that appear under the same English word
inside a single Quarkus JVM.

## Language

### Servers and listeners

**Smithy server**:
An instance of `software.amazon.smithy.java.server.Server`. Owns its own Netty
listener, port, and lifecycle.
_Avoid_: "the server" without qualifier when the Quarkus HTTP server is also
in scope.

**Quarkus HTTP server**:
The Vert.x-based HTTP server provided by `quarkus-vertx-http`. Hosts
`/q/dev`, `/q/health`, and other management endpoints. Runs on a different
port than the Smithy server. The extension does not interact with it.
_Avoid_: "Vert.x server", "HTTP server" without qualifier.

**Smithy server lifecycle**:
The CDI bean (`SmithyServerLifecycle`) that calls `Server.start()` on
`StartupEvent` and `Server.shutdown()` on `ShutdownEvent` for every Smithy
server bean discovered by Arc.

**Separate-server mode**:
The arrangement in which the Smithy server and the Quarkus HTTP server bind
independent ports. This is the only mode supported today.
_Avoid_: "two-port mode", "dual-server mode".

**Unified-server mode**:
The hypothetical arrangement in which Smithy operations are dispatched from
the Quarkus HTTP server's Vert.x router on the same port. Not implemented.
Named here only so future discussion has a stable label; the equivalent in
`quarkus-grpc` is `quarkus.grpc.server.use-separate-server=false`.
Reaching this mode requires a **Vert.x ServerProvider** (transport-side
prerequisite) and a **server-side interceptor SPI** (auth-side prerequisite);
see [ADR-0003](../../docs/adr/0003-defer-shared-transport-and-interceptor-spi.md).

**Vert.x ServerProvider**:
A hypothetical `software.amazon.smithy.java.server.ServerProvider`
implementation whose transport is Vert.x rather than Netty — the missing
SPI binding that would let a Smithy `Server` mount on the Quarkus HTTP
server's `Router` instead of owning its own listener. Today the only impl
is `NettyServerProvider` from `:server:server-netty`. Could live inside
`quarkus-smithy` itself (no upstream change required); deliberately
deferred during the experimental phase.
_Avoid_: "Vert.x transport", "router-mounted server" — both ambiguous.

**Server-side interceptor SPI**:
A hypothetical extension point in `:server:server-api` that lets third
parties inject before/after-operation behavior — the Smithy analog of
gRPC's `ServerInterceptor` or Quarkus REST's `Filter`. Today
`server-api` exposes only `OperationFilters` (allow/blocklist by
operation name) and a stub `RequestContext`; there is no place to
attach a `SecurityIdentity`, a metrics tap, or a request log. Required
upstream for Quarkus-native auth integration regardless of transport.
_Avoid_: confusing with Smithy's existing client-side `ClientInterceptor`
surface; this is the server analog, which does not yet exist.

### Programming model

The extension's `CodeGenProvider` is **mode-agnostic**: it runs whatever
`modes` (`client`, `server`, `types`) the user puts in `smithy-build.json`.
The four models below name the user-side shapes that result. Three are
supported today; two are named-but-deferred so that future discussion has
stable labels.

**Server-bean model** (supported):
The user supplies a CDI producer method that returns a fully built
`Server` (Netty), built from `modes: ["server"]` codegen output. The
extension manages its lifecycle.
_Was previously called_: "@Produces Server" model.
_Avoid_: "manual server", "explicit server".

**Typed-client model** (supported):
The user generates a Smithy client (`modes: ["client"]`) and exposes it as
a CDI bean via a `@Produces` method. There is no Smithy server bean. The
**Smithy server lifecycle** finds zero `Server` beans and is a no-op.
Demonstrated by `examples/quarkus-client/`.

**Types-only model** (supported):
The user generates only Smithy data classes (`modes: ["types"]`) and uses
them however they like — typed JAX-RS payloads, Vert.x route handlers,
internal DTOs. No Smithy server, no Smithy client. Demonstrated by
`examples/quarkus-types/`.

**External-dispatch model** (named, deferred):
The hypothetical model in which the user generates `modes: ["server"]`
operation interfaces but dispatches them from somewhere other than
`software.amazon.smithy.java.server.Server` — e.g., a Vert.x route, a
JAX-RS resource, an MCP handler, a Lambda handler. Not demonstrated as an
example because Smithy-Java does not yet ship a non-Netty `Service`
adapter; users would have to hand-roll the dispatcher today. Adjacent to
**unified-server mode** but distinct: unified-server mode is a
single-port arrangement that still goes through `Server`, while
external-dispatch bypasses `Server` entirely.

**Annotation-discovery model** (named, deferred):
The hypothetical future programming model in which a CDI annotation (e.g.
`@SmithyService`) marks operation implementations and the extension builds
the `Server` itself, analogous to `quarkus-grpc`'s `@GrpcService`. Not
implemented; called out so that "the model we did not pick" has a name.

**Pre-configured-builder model** (named, deferred):
The variant of **Server-bean model** in which the extension produces a
`ServerBuilder<?>` already populated from `quarkus.smithy.server.*`
keys; the user's producer method reads
`Server server(ServerBuilder<?> builder)` and only adds services before
calling `.build()`. Compatible with ADR-0001 (user still owns `Server`
construction). Deferred because Smithy-Java's `ServerBuilder` exposes
only `endpoints` and `numberOfWorkers` today — a richer set of upstream
setters (TLS, request timeouts, max-message-size, graceful-shutdown
grace) is the precondition for the abstraction being worth introducing.
Will be revisited when upstream grows those setters.
_Avoid_: "config-driven model" — every model can be config-driven by
having the user inject `@ConfigProperty`; the distinguishing trait of
this model is who owns the URI assembly.

### Smithy concepts (as used inside this extension)

**Smithy `Service`**:
A generated stub class corresponding to a `service` shape in the user's
`.smithy` model. The user attaches operation implementations to it via its
builder.

**Smithy operation**:
A generated interface for a single RPC, implemented by the user. The
implementation class is referenced by name in the `Server.builder()` call
inside the `@Produces Server` method.

**`java-codegen` plugin**:
The Smithy build plugin (from `:codegen:codegen-plugin`) that turns
`.smithy` shapes into Java source. The extension only honors this plugin
inside `smithy-build.json`; `smithy-base` Gradle plugin wiring is
intentionally not used.

## Relationships

- A Quarkus application has zero or more **Smithy server** beans and exactly
  one **Quarkus HTTP server**.
- The **Smithy server lifecycle** observes Quarkus startup and shutdown
  events; it does not interact with the **Quarkus HTTP server**. When zero
  **Smithy server** beans exist (e.g. **Typed-client model**,
  **Types-only model**), it is silently a no-op — Smithy-side codegen
  still runs.
- The **Server-bean model**, **Typed-client model**, and **Types-only model**
  are supported today; the **External-dispatch model**,
  **Annotation-discovery model**, and **Pre-configured-builder model** are
  named to mark boundaries, not because they exist.
- The **Pre-configured-builder model** is the closest variant to the
  current **Server-bean model** — it differs only in who reads the
  `quarkus.smithy.server.*` keys. Picking it up later requires no change
  to ADR-0001.
- The extension is in **separate-server mode** by construction;
  **unified-server mode** is not reachable until both prerequisites land:
  a **Vert.x ServerProvider** (which we could write inside this extension)
  and a **server-side interceptor SPI** (which has to land upstream in
  `:server:server-api`). Both are deferred during the experimental phase;
  see [ADR-0003](../../docs/adr/0003-defer-shared-transport-and-interceptor-spi.md).
  **Unified-server mode** and **External-dispatch model** are distinct
  — see their definitions above.

## Example dialogue

> **Dev:** "Where does the request hit first — the **Quarkus HTTP server** or
> the **Smithy server**?"
> **Domain expert:** "They're separate listeners on separate ports. A `curl`
> against `:8888` lands on the **Smithy server**'s Netty stack. A `curl`
> against `:8080/q/health` hits the **Quarkus HTTP server**'s Vert.x router.
> The two never share a request."

> **Dev:** "Why don't we discover operation impls via a `@SmithyService`
> annotation like `quarkus-grpc` does?"
> **Domain expert:** "That's the **annotation-discovery model**. We
> deliberately stayed on the **Server-bean model** during the
> experimental phase: the producer body is byte-identical to the upstream
> `BasicServerExample.run()`, so users learning Smithy-Java only have to
> learn Quarkus's `@Produces` wrapper. Annotation discovery is the next
> ergonomic step once codegen stabilizes."

> **Dev:** "I just want a Smithy *client* in my Quarkus app. Do I still
> need the Server lifecycle bean?"
> **Domain expert:** "That's the **Typed-client model**. The lifecycle
> bean is harmless — `Instance<Server>` resolves to nothing and the
> startup/shutdown observers no-op. You generate `modes: [\"client\"]`,
> publish the generated client via your own `@Produces` method, and
> inject it wherever you need it. See `examples/quarkus-client/`."

> **Dev:** "I only want the Smithy types as POJOs for my JAX-RS
> endpoints. Is that supported?"
> **Domain expert:** "Yes — that's the **Types-only model**. Use
> `modes: [\"types\"]` in `smithy-build.json`. You don't even need
> `server-api` or `client-core` on the runtime classpath; just `core`
> (transitive from anything you generate). See
> `examples/quarkus-types/`. If you also want Smithy's serdes (rather
> than Jackson) on the wire, that's still **Types-only** — the model
> name describes what's *generated*, not how the bytes are encoded."

## Flagged ambiguities

- "Server" was used in the README and code comments to mean both the
  **Smithy server** and the **Quarkus HTTP server**. Resolution: these are
  distinct, named separately, and the unqualified word "server" should be
  avoided when both are in scope.
- "Service" can mean a Smithy `service` shape, a Smithy `Service` generated
  stub, or a CDI `@ApplicationScoped` bean. Resolution: only the first two
  are project-specific; CDI services are referred to as "beans".
- "@Produces Server model" was previously used as the only programming-model
  name. Once **Typed-client model** and **Types-only model** were named, the
  term was renamed to **Server-bean model** so that all programming-model
  names parallel each other (server-bean, typed-client, types-only,
  external-dispatch, annotation-discovery). Old references in ADR-0001 are
  preserved for historical accuracy; new prose uses the new name.
