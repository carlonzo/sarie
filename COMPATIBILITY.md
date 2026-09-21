# Compatibility matrix

## OkHttp versions

The bridge is `compileOnly` against the **oldest** supported OkHttp so it cannot pick up
newer APIs and does not pull a version into the host app. The plugin allowlists the same
set at build time (`RecipeRegistry`). Hosts keep their own `implementation("okhttp:…")`.

| This library line | OkHttp | Notes |
| --- | --- | --- |
| unpublished (this repo) | 5.4.0, 5.5.0 | 5.4.0 is the compile floor; 5.5.0 is the sample/default |

When a new OkHttp **shares** internals with this family, add it to `RecipeRegistry`, the
CI matrix in `.github/workflows/pr.yml`, and this table (a **minor** of this library).

When a new OkHttp **breaks** internals (`initExchange` / `copy$okhttp$default` /
`ConnectInterceptor` shape / `Call.addEventListener`):

1. Raise the compile floor and drop the old OkHttp from the table (**minor**, if the
   published coordinate stays the same family, or **major** if the rewrite/bridge ABI
   is a new family).
2. Leave the previous library line published. Apps that cannot bump OkHttp stay on that
   line.
3. **Patch** the old line (`1.0.1` while `1.1.0` has a higher min) for bugfixes that
   still apply on the old internals. Do not mix two internals families in one AAR.

PR CI runs `:bridge:testDebugUnitTest` and `:sample:assembleDebug` against **every**
supported OkHttp (`-PokhttpVersion=`), plus `:sample:connectedDebugAndroidTest` and
`:sample:connectedMinifiedReleaseAndroidTest` on an API-30 emulator (same matrix).
The catalog workflow (`.github/workflows/catalog.yml`) runs only when
`gradle/libs.versions.toml` changes **and** the `okhttp =` pin moved. If the new
version matches the canonical ConnectInterceptor shape and JVM/plugin/assemble
tests pass, it commits goldens, `RecipeRegistry`, and the PR matrix back to the
branch (`scripts/pin-okhttp-version.sh`). A shape mismatch (new internals family)
fails without pinning — raise `okhttpMin` by hand. Host apps that consume the
published plugin still only **warn** on untested versions.

What the Cronet path does and does not do, exactly as the committed test suites prove it.
Every row cites at least one real test class/method from this repo's suites
(`BaselineSuite` / `CronetSuite` / `MinifiedSuite` on device; `CronetBridgeTest` /
`PolicyEngineTest` / `ResponseConverterTest` / `OkHttpBridgeCallbackTest` on the JVM).
These suites are the contract: if a row changes, a test changes.

Routing decisions are made **pre-send** by `PolicyEngine.shouldHandle` on the effective
`Interceptor.Chain` configuration — never after a Cronet request started. Every fallback
below means: stock OkHttp executes the exchange exactly as it would without this project.

| # | Behavior | Contract | Evidence |
| --- | --- | --- | --- |
| 1 | Application interceptors | Run normally — they sit above the swap point and observe the response the bridge returned. Adding interceptors via `newBuilder()` after install still runs them; clearing the interceptor list cannot disable the bridge. | `BaselineSuite.applicationInterceptorsStillRun`, `BaselineSuite.newBuilderInterceptorsRun`, `BaselineSuite.interceptorsClearCannotDisableBridge` |
| 2 | Redirects / follow-ups | `RetryAndFollowUpInterceptor` still functions above the swap: the Cronet path returns the 3xx with an empty body and never calls `followRedirect()`; each follow-up **re-enters the policy** and is served by Cronet again. | `CronetSuite.redirectFollowsAndReentersCronet`, `OkHttpBridgeCallbackTest.redirect surfaces 3xx with empty body and cancels request` |
| 3 | Custom authenticator (401 retry) | Any non-`Authenticator.NONE` authenticator or proxyAuthenticator routes the whole call to stock with `reason=authenticator` (407 handling in `RetryAndFollowUp` requires an Exchange the Cronet path never creates). | `CronetSuite.authenticatorRoutesToStockFallback`, `PolicyEngineTest.custom authenticator or proxyAuthenticator yields authenticator` |
| 4 | Network interceptors | Never run on the Cronet path: a client with any network interceptor is denied pre-send (`reason=network_interceptors`). | `PolicyEngineTest.network interceptor yields network_interceptors` |
| 5 | HTTPS cache (`client.cache != null`) | Denied pre-send with `reason=cache`: Cronet HTTPS responses are never written to OkHttp's cache. Cleartext traffic to cache-carrying clients still goes through the fallback path unchanged. | `PolicyEngineTest.client cache yields cache`, `BaselineSuite.cleartextGoesToStock` |
| 6 | Response metadata | `handshake`, `networkResponse` (and `cacheResponse`) stay **unset** — no fabricated metadata. `sentRequestAtMillis`/`receivedResponseAtMillis` are populated from bridge-owned clocks: request handoff to Cronet (converter start) and header arrival (`onResponseStarted`/`onRedirectReceived`). | `ResponseConverterTest.timestamps populated through the real callback and ordered`, `ResponseConverterTest.response request field is the original request` |
| 7 | EventListener | `callEnd` fires at **header return** while the body streams (the chain's finally path runs without an Exchange); `callTimeout` therefore bounds the header phase only; body reads are bounded by `readTimeout`. | `OkHttpBridgeCallbackTest.readTimeout stall cancels the request and throws timeout IOException`, `CronetSuite.readTimeoutStallAborts` |
| 8 | 3xx handling | Redirects surface as normal 3xx `Response`s for OkHttp's follow-up logic; the bridge never follows redirects inside Cronet. | `OkHttpBridgeCallbackTest.redirect surfaces 3xx with empty body and cancels request`, `CronetSuite.redirectFollowsAndReentersCronet` |
| 9 | 407 (proxy auth challenge) | Rejected with an IOException — never surfaced as a response (following it would crash `RetryAndFollowUp` with no route/Exchange). Proxy-configured clients are denied pre-send anyway (row 12). | `CronetBridgeTest.407 response is rejected with proxy authentication IOException` |
| 10 | Duplicate request headers | Collapse to the **last value on the wire**: the mapper forwards every value, the pinned engine sends only the last one (server observed `[two]` for `X-Dup: one` + `X-Dup: two`). Recorded limitation, not fixed. | `CronetSuite.hostHeaderAndDuplicatesDocumented` |
| 11 | `Host` header | Always derived from the URL by the engine; an app-set `Host` override header is ignored on the Cronet path. | `CronetSuite.hostHeaderAndDuplicatesDocumented` |
| 12 | Proxy / proxySelector | Client with explicit `proxy`, or a `proxySelector` selecting a non-DIRECT proxy, is denied pre-send (`reason=proxy`). | `PolicyEngineTest.explicit proxy yields proxy`, `PolicyEngineTest.custom proxySelector yields proxy` |
| 13 | Certificate pins | Non-empty pins for the target host (not registered as SDK pins) denied pre-send (`reason=pins`). | `PolicyEngineTest.certificate pins yields pins` |
| 14 | Socket factory / hostname verifier | Custom `socketFactory` class or non-default `hostnameVerifier` denied pre-send (`reason=socket_factory` / `reason=hostname_verifier`). | `PolicyEngineTest.custom socketFactory class yields socket_factory`, `PolicyEngineTest.custom hostnameVerifier yields hostname_verifier` |
| 15 | `Dns` | No `dns` fallback reason exists: the `Dns` interface (including IP unmapping) is simply bypassed on the Cronet path — Cronet performs its own resolution and never sees the Dns-mapped addresses. | `PolicyEngineTest.protocols and engine_cold are never routing reasons` (full rule-set coverage proves the enum has no dns case) |
| 16 | Custom trust / TLS | Trust manager whose **class** differs from the captured platform default, OR whose `acceptedIssuers` fingerprint differs (same-class case), denied pre-send (`reason=trust`). Verdicts memoized per TM instance, bounded at 1024. | `PolicyEngineTest.custom sslSocketFactory and TM classes yield trust`, `PolicyEngineTest.custom TM with platform default class but different issuers yields trust (Metis B1)`, `PolicyEngineTest.trust verdicts memoized per TM instance and bounded at 1024` |
| 17 | WebSocket | Stays native: an `https://` upgrade attempt is denied pre-send (`reason=websocket`); a cleartext `ws://` upgrade hits the cleartext rule first. The upgrade request does reach the trampoline in OkHttp 5.5.0 — it is denied/fallback like any request. | `BaselineSuite.webSocketStaysNative`, `MinifiedSuite.webSocketUpgradeStillNative`, `PolicyEngineTest.websocket call yields websocket` |
| 18 | Cleartext / loopback / allowlist | Any `http://` denied (`reason=cleartext`); HTTPS to loopback hosts denied with `reason=cleartext` unless `DefaultPolicy(allowLoopbackHttps = true)`. Empty `allowedOrigins` (the `DefaultPolicy` default) and the `"*"` token admit every origin that passed the earlier rules; a non-empty set is exact-match (`host` = port 443, `host:port` is exact). | `BaselineSuite.cleartextGoesToStock`, `PolicyEngineTest.loopback https with allowLoopbackHttps yields allow`, `PolicyEngineTest.empty allowedOrigins admits every https origin`, `PolicyEngineTest.star token admits every https origin`, `PolicyEngineTest.allowlist port semantics - bare entry means default port 443, host with port is exact` |
| 19 | Kill switch (runtime) | System property `okhttp.cronet.enabled=false` (or `policy.enabled=false`) restores stock behavior immediately, no rebuild. | `BaselineSuite.killSwitchRestoresStock`, `CronetSuite.killSwitchRoutesStock`, `MinifiedSuite.killSwitchRestoresStock`, `PolicyEngineTest.kill switch off yields disabled` |
| 20 | Engine not installed | Falls back to stock with `reason=engine_missing`. | `CronetSuite.engineMissingFallsBackStock`, `PolicyEngineTest.null snapshot yields engine_missing` |
| 21 | Opt-out tag | `request.tag(CronetOptOut::class.java, CronetOptOut)` denies pre-send (`reason=tag_opt_out`). | `PolicyEngineTest.CronetOptOut tag yields tag_opt_out` |
| 22 | `H2_PRIOR_KNOWLEDGE` | Denied pre-send (`reason=h2_prior_knowledge`) — Cronet performs its own protocol negotiation. | `PolicyEngineTest.H2_PRIOR_KNOWLEDGE protocol yields h2_prior_knowledge` |
| 23 | HTTP/3 | **Public** h3 origin through the trampoline negotiates `Protocol.HTTP_3` on device. **Local-origin QUIC is blocked by Chromium's known-root policy** (`ERR_QUIC_CERT_ROOT_NOT_KNOWN`): locally-anchored CAs can never prove h3, regardless of chain completeness or builder knobs — the local origin deterministically serves h2. Server-side h3 capability of the local origin is proven separately via the Caddy access log. Netlog proof of the known-root rejection: `.omo/evidence/okhttp-cronet-transport/task-9/h3-blocker-netlog.json` (CERTIFICATE_VERIFIED followed by CONNECTION_CLOSE, alert 46). | `CronetSuite.h3NegotiatedAgainstPublicOrigin`, `CronetSuite.h2LocalOriginWhileQuicBlocked`, `MinifiedSuite.publicOriginH3ThroughTrampoline` |
| 24 | Compression | The pinned engine **replaces** the caller's `Accept-Encoding` with its own `gzip, deflate, br` — observed server-side even for an explicit caller header (no per-request knob exists on cronet-api 143.7445.0: the mapper hook operates at the builder level, the replacement happens natively at request execution; `enableBrotli` only affects brotli ADVERTISING) — and transparently decodes gzip **and** br. The bridge therefore keeps Cronet's decode and strips `Content-Encoding`/`Content-Length` when all encodings were engine-handled: exactly one decode ever happens (engine-owned), never a header/body mismatch. Identity bodies keep `Content-Length` verbatim; unknown/mixed encodings pass through untouched. | `CronetSuite.acceptEncodingCallerHeaderReplacedAndDecodeKeptConsistent`, `CronetSuite.compressionGzipDecoded`, `ResponseConverterTest.all-Cronet-handled Content-Encoding strips encoding and length headers`, `ResponseConverterTest.identity passthrough keeps Content-Length` |
| 25 | Upload / streaming | POST bodies (1 MiB verified byte-exact) and incremental reads (chunked, ordered, caller byteCount honored) work on the Cronet path. | `CronetSuite.postUploadByteExact`, `CronetSuite.streamingLargeBody`, `OkHttpBridgeCallbackTest.streaming reads deliver chunks in order to completion` |
| 26 | Cancellation | 5-step ordered protocol via public `Call.addEventListener`: cancel-before-attach aborts before any engine start; cancel between attach and start delivers **exactly one** engine cancel; cancel mid-body aborts the pending read with `IOException("Canceled")`. Latency: delivery rides the OkHttp event path + the engine's cancel, not a poll loop; a cancel after full read is a no-op. | `CronetBridgeTest.cancel before attach aborts before any engine start`, `CronetBridgeTest.cancel between attach and start delivers exactly one engine cancel`, `CronetBridgeTest.cancel after headers mid-body aborts the read with Canceled`, `CronetSuite.cancelAfterHeadersAbortsBody` |
| 27 | Timeout model | Header wait and every body read bounded by `readTimeout` (stall aborts with a timeout IOException); `readTimeoutMillis == 0` is effectively infinite. `callTimeout` bounds only the header phase (row 7). | `CronetSuite.readTimeoutStallAborts`, `OkHttpBridgeCallbackTest.readTimeoutMillis zero means effectively infinite` |
| 28 | R8 survival | The trampoline, bridge runtime, policy and metrics survive full minification with **no blanket keeps** of `okhttp3.internal.**` or `sarie.bridge.**` — internals stay renamable; only the test-facing surface is pinned by name in `sample/proguard-rules.pro`. | `MinifiedSuite.cronetPathStillServes`, `MinifiedSuite.cleartextBypass` |
| 29 | Fallback shape | The exact-stock fallback invokes `initExchange$okhttp` + `copy$okhttp$default` + `proceed` — byte-shape identical to stock `ConnectInterceptor` — and references `ConnectInterceptor` nowhere. | `CronetBridgeTest.fallback bytecode shape - initExchange and copy referenced and ConnectInterceptor absent` |
| 30 | Transport-failure retry | Compensates for the missing Exchange-based route retry: a pre-headers transport failure (`onFailed` -> `headersFuture` completed exceptionally with a `CronetException`) is retried **exactly once** with a fresh UrlRequest — only for idempotent methods (GET/HEAD/OPTIONS), only while the call is not canceled, and only when `client.retryOnConnectionFailure` is true. Header-wait timeouts and cancellations are terminal; nothing is ever retried after `onResponseStarted`. Each retry is counted in `Metrics.retries`. | `CronetBridgeTest.pre-headers transport failure retries once for GET and returns response`, `CronetBridgeTest.pre-headers transport failure twice for GET throws after exactly two attempts`, `CronetBridgeTest.non-idempotent POST pre-headers failure is terminal - no retry`, `CronetBridgeTest.cancel during the first attempt prevents the retry`, `CronetBridgeTest.retryOnConnectionFailure false disables the retry` |

## Non-goals (not implemented, so not tested)

`handshake`/`networkResponse` synthesis, network-interceptor execution, OkHttp cache
writes, call-timeout-over-body, desktop JVM support, and `cronet-fallback`
(JavaCronetEngine) as a "Cronet" result. Nothing here promises parity beyond the rows above.
