# Sarie

Let an OkHttp 5.4 / 5.5 Android app speak HTTP/3 through Cronet without forking OkHttp
and without installing an interceptor.

A Gradle plugin rewrites one OkHttp method at build time. Requests then pass through a
bridge that sends matching HTTPS calls over Cronet and everything else over stock OkHttp.
The bridge sits below every interceptor you add. Unsupported configurations go back to
stock OkHttp before any Cronet I/O starts.

See `COMPATIBILITY.md` for the exact contract and `ROLLBACK.md` to turn it off.

## Requirements

- Android **application** module (the rewrite runs when packaging the APK)
- OkHttp **5.4.0** or **5.5.0** (newer versions warn; older versions including OkHttp 4 fail the build)
- minSdk 24
- A host-owned `CronetEngine` (this project never downloads or selects an engine)

## Integration

### 1. Apply the plugin

Apply it to the **application** that packages the APK. That is where OkHttp is rewritten,
including when OkHttp is only a dependency of an Android library:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}
```

AGP cannot rewrite dependency classes into a library AAR (`InstrumentationScope.ALL` is
application-only). You can also apply the plugin to the library that declares OkHttp —
the version/fingerprint guards run there — but you still need it on the application for
the `ConnectInterceptor` rewrite.

### 2. Add dependencies

You keep owning OkHttp. The bridge does not pull a version into your graph.

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0") // 5.4.0 also supported
    implementation("com.carlonzo.sarie:bridge:0.1.0-SNAPSHOT") // or project(":bridge") in this repo
    implementation("org.chromium.net:cronet-embedded:143.7445.0")
}
```

The host owns the engine. This repo's sample and connected suites pin
`cronet-embedded:143.7445.0`. Google later published `500.0.2`, where `cronet-embedded`
is a deprecated empty artifact that pulls `org.chromium.net:cronet-bundled` (and
`cronet-api` pulls `org.chromium.net:cronet`). Either coordinate works at the API this
bridge compiles against; we have not re-run the connected suites on 500.

There is no Play Services provider waterfall.

### 3. Build an engine and install it once at startup

```kotlin
val engine = CronetEngine.Builder(context)
    .enableQuic(true)
    .enableHttp2(true)
    .enableBrotli(true)
    .setStoragePath(context.cacheDir.absolutePath)
    .build()

CronetRuntime.install(engine)
```

That is the whole required API: a host-owned engine, installed once before the first
call. `install` stores a reference, `uninstall()` drops it, and neither calls
`engine.shutdown()`.

`DefaultPolicy()` (the default) sends every origin that passes the other fail-closed
checks over Cronet. Restrict it when you have only tested some hosts — the Cronet path
is not OkHttp-parity (no cache writes, no network interceptors, no handshake metadata;
see `COMPATIBILITY.md`):

```kotlin
CronetRuntime.install(
    engine,
    DefaultPolicy(allowedOrigins = setOf("api.example.com")), // "host" = port 443; "host:port" is exact
)
```

`addQuicHint(host, port, alternatePort)` is a Cronet engine knob, not a Sarie API. It
tells Cronet "this host already speaks QUIC" so the **first** connection can attempt
HTTP/3 instead of waiting for an Alt-Svc advertisement after TCP/H2. It is optional. A
disk cache (`setStoragePath` plus `enableHttpCache`) also lets later sessions reuse QUIC
server config. Wrong hints waste a QUIC attempt and fall back to TCP.

```kotlin
CronetEngine.Builder(context)
    .enableQuic(true)
    .addQuicHint("api.example.com", 443, 443)
    .build()
```

`RequestToUrlRequestMapper` is an optional hook on `UrlRequest.Builder` for Cronet-only
knobs (priority, traffic-stats tag, annotations) that OkHttp's `Request` cannot express.
Most hosts never pass one; the default is a no-op.

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
| HTTPS on a default client (empty allowlist, or a listed origin) | Cronet (h3 or h2, per the engine) |
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
