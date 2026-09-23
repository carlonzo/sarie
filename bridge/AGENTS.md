# bridge/AGENTS.md

Read this first: this module is the runtime transport and routing policy. The trampoline
target `sarie.bridge.CronetBridge` lives here. It is deliberately not a thin
wrapper: for allowed requests it replaces the bottom of OkHttp's network stack, so every
OkHttp-internal responsibility has a Cronet counterpart in this module.

## Request flow

Behavior that this flow produces is `../COMPATIBILITY.md`. Do not duplicate that contract here.

```
ConnectInterceptor.intercept (full replace)
  -> CronetBridge.intercept(chain)
  -> PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), SarieBridge.snapshot())
null (allow):
  RoutedCycle.open; proceed with no exchange (network interceptors run)
  -> CallServerInterceptor prefix -> CronetBridge.callServer
       exchange != null -> null, and the stock body runs
       else RequestConverter -> UrlRequest (CronetExecutor / CronetUploadExecutor)
            -> OkHttpBridgeCallback -> ResponseConverter -> streaming Response
deny:
  stockFallback: initExchange + copy(exchange=) + proceed
  SarieListener.onRouted on both branches (null reason on allow)
```

Two cache call sites are separate from this flow: `CacheHooks.expectTlsBlock` and
`CacheHooks.requireHandshake`. With Sarie off they are the stock `isHttps` checks.

## File inventory (and why each exists)

- `CronetBridge.kt`: trampoline target. `intercept` routes. Allow registers a cycle and
  proceeds with no exchange; `callServer` runs the Cronet path and returns null when
  `exchange != null` so the stock body runs. `stockFallback` re-implements the literal stock
  `ConnectInterceptor` body (`index` is private, so `copy$okhttp` full-arg is unreachable
  without reflection; `copy(exchange = exchange)` is what stock itself compiles to). 407
  becomes `ProtocolException` with stock's message, never a `Response`. See `COMPATIBILITY.md`.
- `mapping/RequestConverter.kt`, `mapping/UploadDataProviders.kt`,
  `mapping/ResponseConverter.kt`, `mapping/OkHttpBridgeCallback.kt`: the OkHttp-to-Cronet
  protocol converters, ported from Google's cronet-transport-for-okhttp (Apache-2.0 headers
  must stay). The callback translates Cronet's async callbacks into a synchronous header
  future plus a streaming body source.
- `PolicyEngine.kt`, `PolicyInput.kt`, `TrustBaseline.kt`: pre-send routing.
  `shouldHandle` returns `Metrics.Reason?` (null allows). Rule order is the `PolicyEngine`
  doc comment (authenticators, OkHttp's cache, and network interceptors are not denies).
  Includes a TLS check on the trust manager's `acceptedIssuers` fingerprint, not just its
  class. Allowlist entries are parsed once onto `RuntimeSnapshot.originRules`. The platform
  socket-factory class and the last trust verdict are cached on `TrustBaseline`. Which rules
  exist is `COMPATIBILITY.md`.
- `CacheHooks.kt`: the two cache `isHttps` replacements. `SarieBridge.isEnabled()` false
  makes them the stock checks.
- `SarieBridge.kt`, `SarieEngineBuilder.kt`, `CronetProviders.kt`, `PinTranslation.kt`,
  `RuntimeSnapshot.kt`, `CronetPolicy.kt`, `DefaultPolicy.kt`, `CronetOptOut.kt`: lifecycle.
  The host may call `install(context, client)`, which builds the engine. `install(engine)`
  remains the borrowed path. The bridge never calls `shutdown()` on either. `RequestConverter`
  and `ResponseConverter` are built once onto the snapshot. `DefaultPolicy()`
  admits every origin that passes the other rules; a non-empty `allowedOrigins` is optional.
  Kill switch via system property `okhttp.cronet.enabled=false`.
- `RoutedCycle.kt`: per-call scheme, host, and port for the allow branch, plus whether the terminal hop was reached.
- `CallRegistry.kt`: cancellation. 5-step ordered protocol with a per-call `EventListener`
  (public `Call.addEventListener`) and a single-delivery CAS so the engine is canceled exactly
  once.
- `CronetExecutor.kt` (`CronetExecutor`, `CronetUploadExecutor`): two DISTINCT single-thread
  executors on purpose. Cronet posts `UploadDataProvider.read()` onto the upload executor
  while the provider submits body work to the reader executor; one shared thread
  self-deadlocks until write timeout.
- `Metrics.kt`: `Reason`, the pre-send deny enum delivered to `SarieListener.onRouted`.
- `SarieListener.kt`: optional `onRouted` / `onFinished`. `onFinished` runs on
  `RequestFinishedExecutor`, not `CronetExecutor`.
- `RequestToUrlRequestMapper.kt`, `BridgePlaceholders.kt`, `VerifiedOkHttpVersions.kt`:
  optional `UrlRequest.Builder` hook (default no-op) and generated support.

## Invariants

- Four sites, descriptors exact, all `@JvmStatic` where the plugin calls them:
  - `CronetBridge.intercept` `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`
    (`ConnectInterceptor` full replace).
  - `CronetBridge.callServer` `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`
    (`CallServerInterceptor` prefix).
  - `CacheHooks.expectTlsBlock` `(Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z`.
  - `CacheHooks.requireHandshake` `(Lokhttp3/Request;)Z`.

- Callback overrides in `OkHttpBridgeCallback` stay CPU-only (they run under
  `allowDirectExecutor()` on Cronet's threads). `CacheHooks` is not a Cronet callback.
- Never call `shutdown()` on an engine this bridge built or borrowed.
- 407 always becomes a `ProtocolException`, never a Response.
- Redirects are never followed inside Cronet; 3xx surfaces with an empty body for
  `RetryAndFollowUpInterceptor`.
- OkHttp internals access pattern: `@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")`
  plus compiled references only. Never use reflection for internals.

## Test map

- `CronetBridgeTest`: trampoline bytecode shape, the 3 cancel interleavings, 407 rejection,
  fallback behavior.
- `PolicyEngineTest`: every `Metrics.Reason` and the full rule order.
- `UploadPumpWedgeTest`, `mapping/UploadDataProvidersTest`: upload pump behavior including
  mid-upload abandonment on a real single-thread executor.
- `mapping/*ConverterTest`: protocol mapping (including `h3`/`quic` -> `Protocol.HTTP_3`) and
  Content-Encoding strip logic.

## Pointers

- `../COMPATIBILITY.md`: the test-cited behavior contract. Reference, do not duplicate.
- Apache-2.0 headers in ported mapper files must stay.
