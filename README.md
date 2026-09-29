# Sarie

**Sarie** lets OkHttp 5.x Android apps speak **HTTP/3 (QUIC)** through Chromium's [Cronet](https://developer.android.com/guide/topics/connectivity/cronet) networking stack, without touching your `OkHttpClient` or interceptors.

---

## What is Sarie?

Sarie is a transport bridge for Android apps using OkHttp (and Retrofit). It brings Cronet's **HTTP/3 (QUIC)**, **connection migration** across Wi-Fi and cellular, and Chromium's connection management to your existing OkHttp client.

- **No client changes**: no interceptors to add or reorder, no custom `Call.Factory`.
- **Pre-send safety**: requests Cronet cannot serve (WebSockets, proxies, custom trust, pins Sarie did not install) fall back to stock OkHttp *before* any network I/O.

Why bother: Reddit moved Android feed traffic to HTTP/3 and saw feed failure rate −10.1%, feed latency −1.36%, and slow video starts −14.53%. Read [Part 1](https://www.reddit.com/r/RedditEng/s/qv0X35p4Vq) and [Part 2](https://www.reddit.com/r/RedditEng/s/MetLApJgYm) of their write-up.

---

## How it works

1. **Build-time bytecode rewriting**: the Gradle plugin rewrites four internal OkHttp call sites at packaging time: `ConnectInterceptor` (the bridge trampoline), `CallServerInterceptor` (a prefix), and two cache `isHttps` checks.
2. **Pre-send routing**: allowed HTTPS requests go to Cronet, with the OkHttp cache, network interceptors, and authenticator still in the path. Everything else (cleartext, WebSockets, custom trust, proxies, opt-outs) stays on stock OkHttp.
3. **Transparent to application interceptors**: the swap happens at `ConnectInterceptor`, so logging, tracing, auth, and header interceptors run normally above it.

---

## Requirements

- **Android application module** (the rewrite happens when packaging the APK)
- **OkHttp 5.4.0** or **5.5.0** (older versions, including OkHttp 4, fail the build; newer versions warn)
- **minSdk 24+**
- A Cronet provider on the classpath (embedded/bundled, platform HttpEngine, or Play Services)

---

## Getting started

### 1. Installation

Apply the plugin to your **application** module, even if OkHttp is declared in a library module:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:<okhttp_version>")
    implementation("com.carlonzo.sarie:bridge:<sarie_version>")
}
```

Optional plugin configuration:

```kotlin
sarie {
    enabled.set(true)               // false: no bytecode rewrite and no version guards (default true)
    failOnUntested.set(false)       // true: an untested (newer) OkHttp fails the build instead of warning
    allowUnfingerprinted.set(false) // true: a fingerprint mismatch warns instead of failing
}
```

### 2. Add a Cronet provider

The bridge has **no runtime dependency on Cronet** (`compileOnly` against `cronet-api`). Add one provider; Sarie picks the first available in this order:

| Preference | Provider | Artifact | Min API | Notes |
| --- | --- | --- | --- | --- |
| 1 | App-packaged (embedded / bundled) | `org.chromium.net:cronet-embedded:500.0.2` | 24+ | Ships in the APK; no downloads or IPC. |
| 2 | Play Services | `com.google.android.gms:play-services-cronet:18.1.1` | 24+ (with GMS) | Smallest APK, frequent updates. |
| 3 | Platform HttpEngine | System (`org.chromium.net:cronet`) | 34+ | Used when Play Services is absent or fails. |

Every 500.x Cronet artifact pulls in `org.chromium.net:cronet`, so API 34+ devices get HttpEngine with any Cronet dependency. The Java fallback provider (`HttpURLConnection`) is never used.

With Play Services, Sarie runs `CronetProviderInstaller` in the background during `install`; you don't call `installProvider` yourself. Until it finishes, calls use stock OkHttp (`engine_missing`).

```kotlin
dependencies {
    // Option A: embedded (consistent across devices)
    implementation("org.chromium.net:cronet-embedded:500.0.2")
    // Option B: Play Services (smaller APK)
    // implementation("com.google.android.gms:play-services-cronet:18.1.1")
}
```

### 3. Install at startup

Call `install` off the main thread, as early as you can:

```kotlin
// In Application.onCreate() or a startup background task
SarieBridge.install(context) {
    certificatePinner(client.certificatePinner) // optional: pins to install
    if (BuildConfig.DEBUG) {
        debugLogger(SarieLogger.Logcat) // logs to logcat under tag "Sarie"
    }
}
```

Sarie builds the engine (HTTP/3 and HTTP/2 on, brotli on, stale DNS on) and never shuts it down. Cronet runs in `HTTP_CACHE_DISK_NO_HTTP` mode: QUIC/Alt-Svc state and the DNS host cache persist on disk, but response caching is left to OkHttp's `Cache`. Calls made before `install` returns use stock OkHttp and do not wait.

That's it. Existing `OkHttpClient` and Retrofit code is unchanged:

```kotlin
client.newCall(request).execute().use { response ->
    println(response.protocol) // HTTP_3 when negotiated
}
```

---

## Comparison with `google/cronet-transport-for-okhttp`

Google's [`cronet-transport-for-okhttp`](https://github.com/google/cronet-transport-for-okhttp) inspired Sarie. The main difference is where the bridge sits: Google's is an application interceptor (it must be last, and every client must be configured), while Sarie rewrites `ConnectInterceptor` so every OkHttp call in the app and its dependencies goes through it.

| Feature / Behavior | google/cronet-transport-for-okhttp | Sarie |
| --- | --- | --- |
| **Integration** | Add `CronetInterceptor` or use `CronetCallFactory` per client | Build-time rewrite; no client changes |
| **Interceptor placement** | Must be last; interceptors after it are silently skipped | Below all application interceptors |
| **Network interceptors** (Flipper, Chucker) | Never run | Run before the Cronet hop |
| **Unsupported client config** (proxy, unbridged pins, custom trust, …) | Sent to Cronet anyway; OkHttp config silently bypassed | Falls back to stock OkHttp pre-send; `SarieListener.onRouted` gets the reason |
| **OkHttp cache** | Cronet responses never cached | Cached by OkHttp (a hit has `handshake == null`); Cronet caches no responses |
| **WebSocket** | Fails | Routed to stock OkHttp |
| **Cancellation** | ~500 ms poll loop | Immediate, via `Call.addEventListener` |
| **Request tags** | Dropped; `CronetCallFactory` throws | Preserved |
| **Transport failure** | Terminal | Idempotent calls retried once before headers |
| **HTTP 407 (proxy auth)** | Can crash follow-up logic | Clean `IOException`; proxy clients denied pre-send |
| **Request opt-out** | Separate `OkHttpClient` / `Call.Factory` | Per-request `CronetOptOut` tag |
| **Wire metrics** (bytes, DNS, connect, TTFB) | None | `SarieListener.onFinished` delivers `SarieTimings` |
| **OkHttp version drift** | Breaks at runtime | Plugin fails the build on an unexpected OkHttp shape |
| **Cronet dependency** | Bundled by host or library | None (`compileOnly`); host picks the provider |

Sarie does not fake an `InetAddress`, `Connection`, or handshake for OkHttp's `EventListener`, and network interceptors see decoded bodies because Cronet decompresses first.

---

## Advanced configuration

### Borrowed engine

`SarieBridge.install(engine) { ... }` uses a host-built `CronetEngine` and never shuts it down. It installs no pins, so pinned hosts fall back to stock. Compression and cache mode are whatever the host built, but Sarie calls `disableCache()` on every request, so Cronet never serves or stores Sarie responses.

### Restricting origins

`DefaultPolicy()` allows every HTTPS host that passes the safety checks. To restrict:

```kotlin
SarieBridge.install(context) {
    policy(
        DefaultPolicy {
            allowedOrigins(setOf("api.example.com", "cdn.example.com:443")) // bare host means port 443
        },
    )
}
```

### Custom `Dns` is ignored on Cronet

> [!WARNING]
> Cronet uses Chromium's resolver and cannot take a custom one. `client.dns` is **never called** for requests routed to Cronet (same as Google's library). If you rely on it (host overrides, DoH, blocking), keep those requests off Cronet with `CronetOptOut` or the allowlist.

Tune Cronet's resolver in `configure` with `setDnsOptions`. It replaces Sarie's stale-DNS defaults, so set everything you want in one call.

### First-connection HTTP/3

Cronet normally learns HTTP/3 from an `Alt-Svc` header on a first TCP connection. Add a QUIC hint so the first connection tries HTTP/3, and send one HEAD after `install` to warm it up:

```kotlin
SarieBridge.install(context) {
    configure { builder ->
        builder.addQuicHint("api.example.com", 443, 443)
    }
}

client.newCall(Request.Builder().url("https://api.example.com/").head().build()).execute().close()
```

`configure` runs before Sarie sets brotli, the cache mode, the storage path, and pin bypass. It must not call `addPublicKeyPins`: Sarie appends the OkHttp client's pins afterwards and cannot remove pins already added.

### Request priority

Use the mapper, which sees every OkHttp request:

```kotlin
SarieBridge.install(context) {
    mapper { request, builder ->
        builder.setPriority(priorityFor(request))
    }
}
```

### Opting out

Per request:

```kotlin
val request = Request.Builder()
    .url("https://api.example.com/special")
    .tag(CronetOptOut::class.java, CronetOptOut)
    .build()
```

Process-wide kill switch, no rebuild needed:

```kotlin
System.setProperty("okhttp.cronet.enabled", "false")
```

### Storage directory

`<cacheDir>/cronet-cache` (`cronet-cache-<suffix>` in secondary processes) holds QUIC and `Alt-Svc` state. Deleting it is safe and only drops remembered HTTP/3 state; Android's "Clear cache" removes it too.

---

## Why am I not on HTTP/3?

Enable `debugLogger(SarieLogger.Logcat)` in debug builds and filter Logcat by tag `Sarie`.

- **Install (`INFO`)**, once:
  - `Cronet installed (built|built, reused|borrowed): ...`: engine ready.
  - `No enabled Cronet provider ...`: no provider found; everything stays on OkHttp.
  - `Play Services CronetProviderInstaller failed ...`: fell back to HttpEngine or stock OkHttp.
- **Routing (`DEBUG`)**, per call: `GET https://host/path -> cronet` or `-> okhttp (reason=...)`. See [Getting calls onto Cronet](#getting-calls-onto-cronet).
- **Protocol (`DEBUG`)**, per Cronet call: `-> h3` or `-> h2`.

Routed to Cronet but `-> h2`?

- **Alt-Svc not learned yet**: the first connection is TCP until the server advertises HTTP/3. Use a [QUIC hint](#first-connection-http3).
- **QUIC marked broken**: after a failed handshake (e.g. UDP blocked), Chromium backs off to TCP for that host. Delete `<cacheDir>/cronet-cache` to reset.
- **Local / self-signed roots**: Chromium only allows QUIC with known roots (`ERR_QUIC_CERT_ROOT_NOT_KNOWN`), so Charles, mitmproxy, or private CAs stay on HTTP/2.

---

## Getting calls onto Cronet

Every request is checked before any network I/O. If Cronet can't serve it safely, it goes to stock OkHttp with a `FallbackReason`, logged as `-> okhttp (reason=<value>)`.

**Fixable:**

| `FallbackReason` | Trigger | Fix | Contract |
| --- | --- | --- | --- |
| `pins` | Host has a pin, but the engine is borrowed, pins differ from the installed set, or a single-label `*.host` wildcard is used | `certificatePinner(client.certificatePinner)` on the built install; use exact hosts or `**.host` | [row 13](COMPATIBILITY.md) |
| `allowlist` | Host not in `allowedOrigins` | Add it, or use an empty set / `"*"` | [row 18](COMPATIBILITY.md) |
| `content_type` | Body with nonzero or unknown length and no `Content-Type` | Set a `Content-Type` on the body or headers | [row 35](COMPATIBILITY.md) |
| `engine_missing` | `install` not returned, no provider, or Play Services still initializing | Add a provider; call `install` early off the main thread | [rows 20, 31, 33](COMPATIBILITY.md) |
| `content_encoding` | The request sets its own `Accept-Encoding` (any value, even `gzip`), or a network interceptor sets one that doesn't list `gzip` | Drop the header and let Cronet negotiate and decode | [row 24](COMPATIBILITY.md) |
| `cleartext` (loopback) | HTTPS to `127.0.0.1`, `localhost`, `::1` | `DefaultPolicy { allowLoopbackHttps(true) }` | [row 18](COMPATIBILITY.md) |

**Intentional:** `disabled` (kill switch or `policy.enabled() == false`, [row 19](COMPATIBILITY.md)), `tag_opt_out` (`CronetOptOut` tag, [row 21](COMPATIBILITY.md)).

**Stays on OkHttp by design:**

| `FallbackReason` | Trigger | Contract |
| --- | --- | --- |
| `cleartext` | `http://` scheme | [row 18](COMPATIBILITY.md) |
| `websocket` | `OkHttpClient.newWebSocket` | [row 17](COMPATIBILITY.md) |
| `h2_prior_knowledge` | `client.protocols` contains `H2_PRIOR_KNOWLEDGE` | [row 22](COMPATIBILITY.md) |
| `proxy` | Explicit `Proxy` or non-DIRECT `ProxySelector` | [row 12](COMPATIBILITY.md) |
| `socket_factory` | Custom `SocketFactory` | [row 14](COMPATIBILITY.md) |
| `hostname_verifier` | Custom `HostnameVerifier` | [row 14](COMPATIBILITY.md) |
| `trust` | Custom `X509TrustManager` or CA anchors | [row 16](COMPATIBILITY.md) |
| `policy_error` | Custom `CronetPolicy.enabled()` threw (fails closed) | [row 34](COMPATIBILITY.md) |

Application interceptors always run. Network interceptors also run on the Cronet path.

---

## Metrics

Pass a `SarieListener` to `install`:

- `onRouted`: caller thread, before network I/O. `reason` is `null` for Cronet, otherwise the `FallbackReason`. Tags are on `call.request()`.
- `onResponseStarted`: when Cronet response headers arrive, before the `Response` goes up the chain (so before interceptors and `EventListener.callEnd`). Carries `SarieResponseInfo`: protocol, status, cache status, timestamps, attempt, redirect flag.
- `onFinished`: once per Cronet attempt (a retry reports twice). Carries `SarieTimings`: `dnsMs`, `connectMs`, `tlsMs`, `sendMs`, `ttfbMs`, `totalMs`, raw timestamps, socket reuse, wire `sentBytes`/`receivedBytes` (compressed), error codes, and `Result`. It runs before the body read hits EOF / throws or `close()` returns, unless the provider rejects finished listeners; then it runs later with `deliveredLate = true`.

```kotlin
SarieBridge.install(context) {
    listener(object : SarieListener {
        override fun onRouted(call: Call, reason: FallbackReason?) {
            val operation = call.request().tag(Operation::class.java)
        }

        override fun onResponseStarted(call: Call, info: SarieResponseInfo) {
            val protocol = info.protocol // HTTP_3, HTTP_2, etc.
        }

        override fun onFinished(call: Call, timings: SarieTimings) {
            val wireBytes = timings.receivedBytes
            val ttfb = timings.ttfbMs
        }
    })
}
```

> [!NOTE]
> QUIC can raise p99 latency while reducing timeouts, because slow successes replace outright failures. Compare success rate and timeouts alongside latency percentiles.

---

## Behavior on the Cronet path

- `response.protocol` is `HTTP_3` or `HTTP_2`, as negotiated.
- `EventListener.callEnd` fires when headers arrive; the body streams afterwards.
- `callTimeout` bounds the header phase; body reads are bounded by `readTimeout`.
- The Sarie-built engine advertises `Accept-Encoding: gzip, deflate, br` and decodes transparently.
- `handshake` is always null, including cached HTTPS hits. See [`COMPATIBILITY.md`](COMPATIBILITY.md).

---

## Demo app

The `:demo` module compares `OkHttp (stock)` and `OkHttp + Sarie` side by side, with live metrics, protocol indicators, a log inspector, and Chucker.

```bash
./gradlew :demo:installDebug
```

Scenarios:

1. **Round trip**: 20 sequential GETs to `cloudflare-quic.com`; cold setup and warm p50/p95.
2. **Parallel images**: 100 concurrent image loads from `images.unsplash.com`; wall time, first image, p50/p95/p99.
3. **Connection setup**: one cold GET to each of 4 origins; DNS, connect, TLS, TTFB.
4. **Big download / migration**: ~9 MB on both stacks at once. Switch Wi-Fi ↔ mobile mid-download: QUIC migration keeps Sarie going, TCP breaks.

Simulate a bad network:

```bash
./scripts/netem.sh on 2% 100ms   # 2% loss, 100ms delay
./scripts/netem.sh off
```

---

## Reference

- [`COMPATIBILITY.md`](COMPATIBILITY.md): full behavior contract with test citations.
