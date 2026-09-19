# okhttp-cronet-transport

A standalone Android project that lets any OkHttp 5.4 / 5.5 app speak HTTP/3 through Cronet
without an OkHttp fork and without a user-visible interceptor: a host-app Gradle plugin
rewrites exactly one OkHttp method at build time so requests first pass through a bridge
that routes allowlisted origins over Cronet and everything else over stock OkHttp.

There is no interceptor to install and no `Call.Factory` to swap: the bridge sits **below**
every interceptor the app adds, at `ConnectInterceptor.intercept`, and a pre-send policy
sends every unsupported configuration back to stock OkHttp before any Cronet I/O starts.
See `COMPATIBILITY.md` for the exact (test-cited) contract and `ROLLBACK.md` for the
opt-out/rollback story.

## 60-second integration

Requirements: an Android **application** module, a **supported OkHttp version** (5.4.0 or
5.5.0; see "Toolchain requirements" below), minSdk 24.

### 1. Apply the plugin to your application module

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("dev.okhttpcronet.transport")   // injects the one-method trampoline into OkHttp at build time
}
```

The plugin id `dev.okhttpcronet.transport` resolves from this repo's `plugin-build` via a
composite build (this project's root `settings.gradle.kts` does
`includeBuild("plugin-build")`; a host app consumes it the same way, or later from
published coordinates once published). Library modules are unsupported: the plugin no-ops
with a warning there.

### 2. Add the bridge dependency

```kotlin
// app/build.gradle.kts (dependencies block)
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")   // 5.4.0 or 5.5.0 supported; newer warns, older fails
    implementation(project(":bridge"))                    // published-coordinate placeholder: dev.okhttpcronet.bridge
    implementation("org.chromium.net:cronet-embedded:143.7445.0") // supplies the engine (API + native libs)
}
```

The app must own a real `CronetEngine` — there is no provider waterfall, no engine
download, and no fallback engine. `cronet-embedded` ships the natives;
Google Play Services' provider is not wired up.

### 3. Build a ready engine and install it

```kotlin
val engine = CronetEngine.Builder(context)
    .enableQuic(true)
    .enableHttp2(true)
    .enableBrotli(true)
    .setStoragePath(context.cacheDir.absolutePath)   // QUIC server-config cache
    .addQuicHint("api.example.com", 443, 443)        // skip QUIC racing on the first connection
    .build()

CronetRuntime.install(
    DefaultPolicy(allowedOrigins = setOf("api.example.com")),  // exact host:port, default-deny
    engine,
    RequestToUrlRequestMapper { _, _ -> },            // optional request rewriter, applied pre-build()
)
```

The engine is **borrowed, never owned**: `CronetRuntime.install` stores a reference,
`uninstall()` drops it, and neither ever calls `engine.shutdown()` — stopping the engine
is the host's decision. Install once at app start (before the first call) and leave it.

### 4. Opt a request out of Cronet

```kotlin
val request = Request.Builder()
    .url("https://api.example.com/v1/thing")
    .tag(CronetOptOut::class.java, CronetOptOut)   // pre-send policy denies -> stock OkHttp
    .build()
```

### 5. What runs where

| Request shape | Path | Why |
| --- | --- | --- |
| HTTPS to an allowlisted origin, default client | **Cronet** (h3/h2, per engine negotiation) | policy allows |
| Any `http://` URL, loopback, WebSocket | stock OkHttp | fail-closed pre-send policy (`cleartext` / `websocket`) |
| Client with cache, network interceptors, custom authenticator/proxy/pins/trust/socketFactory/hostnameVerifier, `H2_PRIOR_KNOWLEDGE` | stock OkHttp | policy denies with the matching reason |
| No engine installed, kill switch off, or opt-out tag | stock OkHttp | `engine_missing` / `disabled` / `tag_opt_out` |

Application interceptors still run (they sit above the swap point); network interceptors
never run on the Cronet path. Full row-by-row contract with test citations:
`COMPATIBILITY.md`.

### 6. Fixed vs google/cronet-transport-for-okhttp

The upstream Google transport (same Cronet, OkHttp as a user-facing interceptor library)
has structural limits this project removes. Every row cites the test that proves it.

| Behavior | google/cronet-transport-for-okhttp | This library | Mechanism / evidence |
| --- | --- | --- | --- |
| Interceptor placement | The transport must be installed as the **last** interceptor; removing or reordering it silently drops Cronet. | **Eliminated**: no interceptor to install — a build-time bytecode trampoline replaces exactly one OkHttp method (`ConnectInterceptor.intercept`) and cannot be removed or reordered from app code. | `ConnectInterceptorRewriterTest.rewritten intercept is exactly the trampoline for every recipe variant`, `MinifiedSuite.cronetPathStillServes` |
| WebSocket | WebSocket calls are unsupported on the Cronet path (the transport is not callable for them; the app must special-case). | **Native via stock routing**: upgrade attempts are denied pre-send (`reason=websocket` / cleartext rule) and complete on the exact-stock OkHttp path. | `BaselineSuite.webSocketStaysNative`, `PolicyEngineTest.websocket call yields websocket` |
| Cancellation latency | Cancel reaches the engine via periodic state polling (~500 ms granularity). | **Immediate**: one engine cancel delivered through the public `Call.addEventListener` event path; exactly-once semantics. | `CronetBridgeTest.cancel between attach and start delivers exactly one engine cancel`, `CronetSuite.cancelAfterHeadersAbortsBody` |
| Client network config | A client configured with things Cronet cannot honor (proxies, pins, custom trust, authenticators…) still gets routed to Cronet — the config is **silently bypassed**. | **Fail-closed pre-send routing with reason codes**: 19 ordered rules inspect the effective chain before any I/O; unsupported configs run exact-stock. | `PolicyEngineTest` (full rule-order coverage), `BaselineSuite.authenticatorRoutesToStockFallback` |
| OkHttp tags | Request tags are dropped in the OkHttp→Cronet conversion. | **Preserved**: the host mapper hook re-applies tags (and anything else) to the `UrlRequest.Builder` just before `build()`; the `CronetOptOut` tag drives per-request routing. | `RequestConverterTest.runtime mapper is applied to the builder before build`, `PolicyEngineTest.CronetOptOut tag yields tag_opt_out` |
| OkHttp cache | Fully bypassed: cronet-path responses are never written to the client's `Cache`, even when one is configured. | Cache-carrying clients are routed to **stock** pre-send (`reason=cache`), so the `CacheInterceptor` behaves exactly stock — cache hits short-circuit above the swap point and never reach Cronet. | `PolicyEngineTest.client cache yields cache` |
| 407 responses | A proxy-auth challenge crashes the follow-up logic (no usable route/exchange behind the bridge response). | **Clear IOException** (`"Proxy authentication is not supported over the Cronet path"`); proxy clients are denied pre-send anyway. | `CronetBridgeTest.407 response is rejected with proxy authentication IOException` |
| Transport-failure retry | A failed Cronet request is terminal — no retry at all. | **Idempotent pre-headers retry, exactly once**: GET/HEAD/OPTIONS, not canceled, `retryOnConnectionFailure` honored, only before any response byte; non-idempotent methods stay terminal. | `CronetBridgeTest.pre-headers transport failure retries once for GET and returns response` |
| Response timestamps | `sentRequestAtMillis` / `receivedResponseAtMillis` stay unset. | **Populated** from bridge-owned clocks (converter start / `onResponseStarted`). | `ResponseConverterTest.timestamps populated through the real callback and ordered` |

### 7. What differs when your traffic runs HTTP/3 vs HTTP/2

Facts a caller can observe on the Cronet path, split by the negotiated protocol
(`response.protocol`): `Protocol.HTTP_3` (`h3`) vs `Protocol.HTTP_2` (`h2`).

- `response.protocol` is `HTTP_3` when the engine negotiated h3, `HTTP_2` for h2 —
  `CronetSuite.h3NegotiatedAgainstPublicOrigin`, `CronetSuite.h2FirstRequestWithoutHint`,
  `ResponseConverterTest.negotiated protocol mapping`.
- h3 responses carry **no `handshake` / `networkResponse` / `cacheResponse`** (and neither do
  h2 ones): QUIC-TLS has no OkHttp `Handshake` representation and the bridge never
  fabricates metadata (see `COMPATIBILITY.md` row 6).
- `EventListener.callEnd` fires at **header return** on both protocols (the body keeps
  streaming after it); `callFailed` only if the chain fails earlier — `COMPATIBILITY.md`
  row 7.
- `callTimeout` bounds the **header phase only**; body reads are bounded by `readTimeout`
  regardless of protocol — `CronetSuite.readTimeoutStallAborts`.
- Connection migration and 0-RTT are **engine-managed** (no OkHttp visibility, no events).
- `EventListener` sees **no connect/DNS stages** on the Cronet path — no Exchange exists;
  only the call-level events above fire.
- Duplicate request headers **collapse to the last value on the wire** (engine behavior,
  measured): `CronetSuite.hostHeaderAndDuplicatesDocumented`.
- `Accept-Encoding`: the engine replaces the caller's header with its own
  `gzip, deflate, br` and transparently decodes (exactly one decode, engine-owned; no
  per-request control exists) — `CronetSuite.acceptEncodingCallerHeaderReplacedAndDecodeKeptConsistent`,
  `CronetSuite.compressionGzipDecoded`.
- Cache writes are skipped for Cronet responses (cache clients are on stock entirely) —
  `PolicyEngineTest.client cache yields cache`.

Local-origin h3 is additionally blocked by Chromium's known-root QUIC policy on the pinned
engine (deterministic h2 fallback; netlog-archived) — `CronetSuite.h2LocalOriginWhileQuicBlocked`,
`COMPATIBILITY.md` row 23.

### 8. Toolchain requirements

- **JDK 21** for building (`JAVA_HOME` must point at a Temurin 21 install; this repo pins
  `/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`).
- **minSdk 24** (bridge and sample), compileSdk 37.
- **OkHttp 5.4.0 or 5.5.0** — the plugin reads the version resolved on the app runtime
  classpath. `verifyOkHttpPin` accepts those supported versions, **warns** if a newer
  untested version is resolved (the structural bytecode guard still runs; the fingerprint
  identity check is skipped), and **fails** on anything older, including every OkHttp 4
  release. `verifyOkHttpFingerprint` SHA-256-checks `ConnectInterceptor.class` against the
  recorded golden for that supported version. Both run before `preBuild`.
- AGP 8.13.0 / Gradle 8.14.3 / Kotlin 2.2.20 / cronet-api + cronet-embedded 143.7445.0
  (full pins and quirks in the table below and `THIRD_PARTY.md`).

### 9. Verify it (this repo's suites)

Every claim in the docs traces to one of these suites; they are the living documentation.

```bash
export JAVA_HOME=/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS

# JVM: mapping, policy, bridge glue, rewriter + fingerprints + TestKit plugin tests
./gradlew :bridge:testDebugUnitTest :plugin-build:plugin:test

# Device: stock/fallback baseline + real-Cronet suite (needs a booted API-30+ emulator).
# The HTTP/3 origin (pinned Caddy 2.11.4) is gradle-managed: connectedDebugAndroidTest
# starts it via startTestOrigin (auto-downloads + health-checks Caddy) and verifies the
# h3 server evidence afterwards.
./gradlew :sample:connectedDebugAndroidTest

# Device, R8-minified variant: critical subset (Cronet path, WebSocket, bypass, kill switch)
./gradlew :sample:connectedMinifiedReleaseAndroidTest
```

One-time origin prep: `./scripts/download-caddy.sh` then `./scripts/gen-certs.sh`
(deterministic local CA; the public cert is bundled as `sample/src/main/res/raw/caddy_root_ca`
and trusted via `sample/src/main/res/xml/network_security_config.xml`).

## Pinned toolchain (verified 2026-09-19)

| Component | Pin | Source |
| --- | --- | --- |
| JDK | Temurin 21.0.12+1 LTS (`/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`) | mise, verified on disk |
| AGP | 8.13.0 | Google Maven metadata (8.13.0 exists; 8.13.1/8.13.2 also published, not used) |
| Gradle | 8.14.3 (wrapper, `-bin` dist) | wrapper pinned in root + plugin-build |
| Kotlin | 2.2.20 | plugin-build `kotlin("jvm")` + catalog `kotlin-android` |
| OkHttp | 5.5.0 (`com.squareup.okhttp3:okhttp`) | catalog |
| Cronet | cronet-api / cronet-embedded 143.7445.0 | catalog |
| compileSdk / minSdk | 37 / 24 | module DSL (`compileSdkVersion = "android-37.0"` — see note below) |

Note: `local.properties` is required (not committed); it points at `/home/carlo/Android/Sdk`.
Plain `./gradlew` works from the project root: `gradle.properties` pins the daemon JVM to
the Temurin 21 path above via `org.gradle.java.home` (machine-specific — on other machines,
set `JAVA_HOME` to any Temurin 21 instead or adjust that property).

Toolchain notes (recorded deviations/quirks):
- The installed platform is the minor-release `android-37.0` (ApiLevel=37.0). Integer
  `compileSdk = 37` makes AGP 8.13.0 look up hash string `android-37` and fail to find it,
  so modules set the string form `compileSdkVersion = "android-37.0"` instead.
- AGP 8.13.0 is tested up to compileSdk 36.1; `android.suppressUnsupportedCompileSdk=37.0`
  is set in `gradle.properties` to silence the recommended-update warning.
- `android.useAndroidX=true` is required: `com.squareup.okhttp3:okhttp:5.5.0` resolves to
  `okhttp-android:5.5.0` on Android, which pulls `androidx.annotation` / `androidx.startup`.

## What this will NOT do

- No OkHttp fork, no republished patched coordinate, no user-visible interceptor.
- No runtime agents, no ART hooking, no reflection on the request hot path.
- No automatic engine waterfall (HttpEngine/GMS selection), no engine download, no engine
  shutdown of the host's engine.
- No desktop JVM transport; `cronet-fallback` (JavaCronetEngine) is not a supported backend.
- No OkHttp feature parity on the Cronet path beyond `COMPATIBILITY.md` — network
  interceptors, HTTPS cache writes, fabricated handshake metadata, and
  call-timeout-over-body are explicitly out.
