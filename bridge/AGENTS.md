# bridge/AGENTS.md

Read this first: this module is the runtime transport and routing policy. The trampoline
target `sarie.bridge.CronetBridge` lives here. It is deliberately not a thin
wrapper: for allowed requests it replaces the bottom of OkHttp's network stack, so every
OkHttp-internal responsibility has a Cronet counterpart in this module.

## Request flow

```
ConnectInterceptor.intercept (rewritten by the plugin)
  -> CronetBridge.intercept(chain)
  -> PolicyEngine.shouldHandle(PolicyInput.fromChain(chain), SarieBridge.snapshot())
allow:
  RequestConverter -> UrlRequest (CronetExecutor / CronetUploadExecutor)
  -> OkHttpBridgeCallback -> ResponseConverter -> streaming Response
deny:
  stockFallback: initExchange + copy(exchange=) + proceed
  + Metrics.record(Path.FALLBACK, reason)
```

## File inventory (and why each exists)

- `CronetBridge.kt`: trampoline target. `intercept` routes; `stockFallback` re-implements the
  literal stock `ConnectInterceptor` body (`index` is private, so `copy$okhttp` full-arg is
  unreachable without reflection; `copy(exchange = exchange)` is what stock itself compiles
  to). 407 is rejected with an IOException because `RetryAndFollowUp` would dereference a null
  Exchange route.
- `mapping/RequestConverter.kt`, `mapping/UploadDataProviders.kt`,
  `mapping/ResponseConverter.kt`, `mapping/OkHttpBridgeCallback.kt`: the OkHttp-to-Cronet
  protocol converters, ported from Google's cronet-transport-for-okhttp (Apache-2.0 headers
  must stay). The callback translates Cronet's async callbacks into a synchronous header
  future plus a streaming body source.
- `PolicyEngine.kt`, `PolicyInput.kt`, `TrustBaseline.kt`: pre-send routing. 19 fail-closed
  rules in fixed order (see the `PolicyEngine` doc comment), including a TLS check on the
  trust manager's `acceptedIssuers` fingerprint, not just its class.
- `SarieBridge.kt`, `RuntimeSnapshot.kt`, `CronetPolicy.kt`, `DefaultPolicy.kt`,
  `CronetOptOut.kt`: lifecycle. The host installs the engine (`install(engine)` is enough);
  the bridge borrows it and never shuts it down. `DefaultPolicy()` admits every origin that
  passes the other rules; a non-empty `allowedOrigins` is optional. Kill switch via system
  property `okhttp.cronet.enabled=false`.
- `CallRegistry.kt`: cancellation. 5-step ordered protocol with a per-call `EventListener`
  (public `Call.addEventListener`) and a single-delivery CAS so the engine is canceled exactly
  once.
- `CronetExecutor.kt` (`CronetExecutor`, `CronetUploadExecutor`): two DISTINCT single-thread
  executors on purpose. Cronet posts `UploadDataProvider.read()` onto the upload executor
  while the provider submits body work to the reader executor; one shared thread
  self-deadlocks until write timeout.
- `Metrics.kt`: `Path` (CRONET/FALLBACK) and `Reason` counters. Tests assert on it; keep
  reasons stable.
- `RequestToUrlRequestMapper.kt`, `BridgePlaceholders.kt`, `VerifiedOkHttpVersions.kt`:
  optional `UrlRequest.Builder` hook (default no-op) and generated support.

## Invariants

- `CronetBridge.intercept` and `CronetBridge.shouldHandle` stay `@JvmStatic` with exact
  signatures; the plugin's emitted bytecode calls them by name and descriptor.
- Callback overrides in `OkHttpBridgeCallback` stay CPU-only (they run under
  `allowDirectExecutor()` on Cronet's threads).
- The engine is borrowed: never call `shutdown()` on it.
- 407 always becomes an IOException, never a Response.
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
