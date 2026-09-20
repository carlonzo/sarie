# Sarie

Let an OkHttp 5.4 / 5.5 Android app speak HTTP/3 through Cronet without forking OkHttp
and without installing an interceptor.

A Gradle plugin rewrites one OkHttp method at build time. Requests then pass through a
bridge that sends allowlisted origins over Cronet and everything else over stock OkHttp.
The bridge sits below every interceptor you add. Unsupported configurations go back to
stock OkHttp before any Cronet I/O starts.

See `COMPATIBILITY.md` for the exact contract and `ROLLBACK.md` to turn it off.

## Requirements

- Android **application** module (library modules are ignored)
- OkHttp **5.4.0** or **5.5.0** (newer versions warn; older versions including OkHttp 4 fail the build)
- minSdk 24
- A host-owned `CronetEngine` (this project never downloads or selects an engine)

## Integration

### 1. Apply the plugin

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}
```

### 2. Add dependencies

You keep owning OkHttp. The bridge does not pull a version into your graph.

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0") // 5.4.0 also supported
    implementation("com.carlonzo.sarie:bridge:0.1.0-SNAPSHOT") // or project(":bridge") in this repo
    implementation("org.chromium.net:cronet-embedded:143.7445.0")
}
```

`cronet-embedded` supplies the engine and native libraries. There is no Play Services
provider waterfall.

### 3. Build an engine and install it once at startup

```kotlin
val engine = CronetEngine.Builder(context)
    .enableQuic(true)
    .enableHttp2(true)
    .enableBrotli(true)
    .setStoragePath(context.cacheDir.absolutePath)
    .addQuicHint("api.example.com", 443, 443)
    .build()

CronetRuntime.install(
    DefaultPolicy(allowedOrigins = setOf("api.example.com")),
    engine,
    RequestToUrlRequestMapper { _, _ -> },
)
```

The engine is borrowed: `install` stores a reference, `uninstall()` drops it, and neither
calls `engine.shutdown()`. Install before the first call.

### 4. Opt a request out

```kotlin
request.newBuilder()
    .tag(CronetOptOut::class.java, CronetOptOut)
    .build()
```

That request runs on stock OkHttp.

## What runs where

| Request | Path |
| --- | --- |
| HTTPS to an allowlisted origin, default client | Cronet (h3 or h2, per the engine) |
| `http://`, loopback, WebSocket | stock OkHttp |
| Client with cache, network interceptors, custom authenticator / proxy / pins / trust / socket factory / hostname verifier, or `H2_PRIOR_KNOWLEDGE` | stock OkHttp |
| No engine, kill switch off (`okhttp.cronet.enabled=false`), or `CronetOptOut` tag | stock OkHttp |

Application interceptors still run. Network interceptors never run on the Cronet path.

## Compared with google/cronet-transport-for-okhttp

| | google/cronet-transport-for-okhttp | This library |
| --- | --- | --- |
| Placement | Must be the last interceptor; removing it silently drops Cronet | No interceptor to install or reorder |
| WebSocket | Unsupported on the Cronet path | Routed to stock OkHttp |
| Cancel | ~500 ms poll | Immediate, via `Call.addEventListener` |
| Unsupported client config (proxy, pins, custom trust, …) | Still sent to Cronet; config is bypassed | Fail-closed to stock OkHttp with a reason |
| Request tags | Dropped | Preserved; `CronetOptOut` is a tag |
| OkHttp cache | Cronet responses never written | Cache clients stay on stock OkHttp |
| 407 | Can crash follow-up logic | Clear `IOException`; proxy clients denied before send |
| Transport failure | Terminal | One idempotent retry before any response byte (GET/HEAD/OPTIONS) |
| Timestamps | Unset | Set from the bridge |

## HTTP/3 vs HTTP/2 on the Cronet path

`response.protocol` is `HTTP_3` or `HTTP_2` according to what the engine negotiated.

On both protocols:

- No `handshake`, `networkResponse`, or `cacheResponse`
- `EventListener.callEnd` fires when headers return; the body may still be streaming
- `callTimeout` bounds the header phase only; body reads use `readTimeout`
- No connect/DNS `EventListener` stages (there is no OkHttp Exchange)
- Duplicate request headers collapse to the last value
- The engine replaces `Accept-Encoding` with `gzip, deflate, br` and decodes the body
- Connection migration and 0-RTT are engine-managed and invisible to OkHttp

## What this will not do

- No OkHttp fork, no republished patched coordinate, no user-visible interceptor
- No runtime agents, ART hooks, or reflection on the request hot path
- No automatic engine waterfall or engine download
- No desktop JVM transport; `cronet-fallback` is not supported
- No OkHttp feature parity on the Cronet path beyond `COMPATIBILITY.md`
