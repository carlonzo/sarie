# Sarie

**Sarie** enables OkHttp 5.x Android applications to speak **HTTP/3 (QUIC)** through Chromium's [Cronet](https://developer.android.com/guide/topics/connectivity/cronet) networking stack — without forking OkHttp and without installing or reordering application interceptors.

---

## What is Sarie?

Sarie is a lightweight transport bridge for Android apps that use OkHttp (and Retrofit). It brings the performance advantages of Cronet — such as **HTTP/3 (QUIC)**, **connection migration** across Wi-Fi and cellular switches, and Chromium's battle-tested connection management — directly to your existing OkHttp client.

Crucially:
- **No OkHttp fork**: Your app continues using official OkHttp coordinates.
- **No user-visible interceptors**: You do not need to add or reorder interceptors or replace your `OkHttpClient` with a custom `Call.Factory`.
- **Pre-send safety**: Requests with configurations Cronet cannot support (e.g. WebSockets, custom proxies, pins Sarie did not install, or a custom `Dns`) automatically and safely route to stock OkHttp *before* any network I/O begins.

---

## How it works

1. **Build-time bytecode rewriting**: The Sarie Gradle plugin rewrites four internal OkHttp call sites at APK packaging time: `ConnectInterceptor` (the bridge trampoline), `CallServerInterceptor` (a prefix), and two cache `isHttps` checks.
2. **Pre-send policy routing**: Every request reaching the connection stage passes through an internal pre-send policy engine:
   - **Allowed HTTPS requests** are converted and dispatched over the high-performance Cronet engine. An OkHttp cache, network interceptors, and a custom authenticator stay on that path.
   - **Unsupported or opt-out requests** (cleartext HTTP, WebSockets, custom trust managers, proxies, a custom `Dns`, or explicit opt-outs) fall back to stock OkHttp's native connection pipeline.
3. **Transparent to application interceptors**: Because the swap happens at `ConnectInterceptor` (the lowest layer of OkHttp's interceptor chain), your logging, tracing, authentication, and header interceptors run normally above the bridge and observe all responses.

---

## Requirements

- **Android Application module** (bytecode rewriting takes place when packaging the APK)
- **OkHttp 5.4.0** or **5.5.0** (older versions including OkHttp 4 fail the build; newer versions warn)
- **Android minSdk 24+**
- A Cronet provider on the classpath (embedded/bundled, platform HttpEngine, or Play Services). Sarie builds the engine. A host-built engine is an advanced option.

---

## First steps

### 1. Installation

Apply the Sarie Gradle plugin to your **application** module (`app/build.gradle.kts`). The rewrite operates on the packaging step where OkHttp classes are assembled into the APK:

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.carlonzo.sarie")
}
```

> **Note**: AGP transforms dependency bytecode at the application level (`InstrumentationScope.ALL`). If OkHttp is declared in an Android library module, you still apply `com.carlonzo.sarie` to the application module packaging the final APK.

Next, add OkHttp and the Sarie bridge to your app's dependencies:

```kotlin
dependencies {
    // OkHttp 5.x
    implementation("com.squareup.okhttp3:okhttp:<okhttp_version>")

    // Sarie bridge runtime
    implementation("com.carlonzo.sarie:bridge:<sarie_version>")
}
```

---

### 2. Add a Cronet provider

Sarie's bridge has **no runtime dependency on Cronet** (`compileOnly` against `cronet-api`). Put one implementation on the app classpath. Sarie automatically selects and initializes the first available provider according to this preference order:

| Preference | Provider | Artifact | Min API | Rationale |
| --- | --- | --- | --- | --- |
| 1 | App-packaged (`org.chromium.net:cronet-embedded` / `-bundled`) | `org.chromium.net:cronet-embedded:500.0.2` | 24+ | Explicit host choice: ships Chromium Cronet directly in the APK; available immediately without external downloads or IPC. |
| 2 | Play Services (`Google-Play-Services-Cronet-Provider`) | `com.google.android.gms:play-services-cronet:18.1.1` | 24+ (with GMS) | Smallest APK size and receives more frequent Cronet updates than the system image; downloads Chromium binaries via Google Play Services dynamically. |
| 3 | Platform HttpEngine (`HttpEngine-Native-Provider`) | System (`org.chromium.net:cronet`) | 34+ (Android 14) | System-managed: updated with OS Mainline modules and used when Play Services is absent or fails to initialize. |

The `HttpEngine` provider class ships in `org.chromium.net:cronet` (which every 500.x Cronet artifact pulls in), so devices running API 34+ get `HttpEngine` automatically with any Cronet dependency.

When using Play Services, Sarie automatically initializes `CronetProviderInstaller` in the background on `install` and builds the engine once available (falling back to HttpEngine if the installer fails)—you do not need to call `installProvider` yourself. While the Play task runs, calls fall back safely to stock OkHttp (`engine_missing`).

A host that wants its own provider order builds its own engine and uses the borrowed install (`SarieBridge.install(engine)`). That path installs no pins, so pinned hosts fall back to stock OkHttp.

Java's fallback provider (`Fallback-Cronet-Provider` / `HttpURLConnection`) is never used.

#### Option A: Embedded / bundled (zero setup, consistent across devices)

```kotlin
dependencies {
    implementation("org.chromium.net:cronet-embedded:500.0.2")
    // equivalent: implementation("org.chromium.net:cronet-bundled:500.0.2")
}
```

#### Option B: Play Services (smaller APK, auto-initialized)

```kotlin
dependencies {
    implementation("com.google.android.gms:play-services-cronet:18.1.1")
}
```

---

### 3. Setting up the library

At startup, call `install` off the main thread as early as you can:

```kotlin
SarieBridge.install(
    context,
    SarieConfig {
        certificatePinner(client.certificatePinner) // optional: pins to install
    },
)
```

A call that arrives before `install` returns goes to stock OkHttp (`engine_missing`) and does not wait for the engine. Pass `certificatePinner` with the `OkHttpClient`'s `CertificatePinner` (or omit for none). Sarie builds the engine — HTTP/3 and HTTP/2 on, brotli off, Cronet's HTTP cache off, stale DNS on — and never shuts it down. You do **not** need to build a `CronetEngine` yourself.

Once that returns, **you are done!**

You do **not** need to touch your `OkHttpClient` setup, register interceptors, or adapt Retrofit builders:

```kotlin
// OkHttp calls automatically use Cronet / HTTP/3
val client = OkHttpClient()
val request = Request.Builder()
    .url("https://api.example.com/data")
    .build()

client.newCall(request).execute().use { response ->
    println("Protocol: ${response.protocol}") // Returns HTTP_3 when negotiated!
}
```

Retrofit works as usual without changes:

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com/")
    .client(okHttpClient) // Or default client
    .build()
```

---

### 4. Advanced configuration & knobs

#### Borrowed engine

`SarieBridge.install(engine)` borrows a host-built `CronetEngine` and does not shut it down. This path loses pins: any host that matches a pin falls back to stock. Compression and the HTTP cache are whatever the host built. Sarie still calls `UrlRequest.Builder.disableCache()` on every request, so that cache does not serve or store Sarie traffic.

#### Restricting origins (Allowlist)
By default, `DefaultPolicy()` allows every HTTPS host that passes safety checks. You can restrict Cronet routing to specific domains:

```kotlin
SarieBridge.install(
    context,
    SarieConfig {
        certificatePinner(client.certificatePinner)
        policy(
            DefaultPolicy.Builder()
                .allowedOrigins(setOf("api.example.com", "cdn.example.com:443")) // bare host assumes port 443
                .build(),
        )
    },
)
```

#### First-connection HTTP/3 (`addQuicHint`)
Cronet normally discovers HTTP/3 after the first connection receives an `Alt-Svc` response header over TCP. Pass a QUIC hint in `configure` so the **very first connection** attempts HTTP/3. `configure` runs before Sarie overwrites brotli, the HTTP cache, the storage path, and local-trust pin bypass:

```kotlin
SarieBridge.install(
    context,
    SarieConfig {
        certificatePinner(client.certificatePinner)
        configure { builder ->
            builder.addQuicHint("api.example.com", 443, 443)
        }
    },
)
```

Stale DNS is on unless `configure` replaces it (`DnsOptions.builder().enableStaleDns(false).build()` passed to `setDnsOptions`). A provider that rejects stale DNS does not fail `install`.

After `install` returns, one cheap HEAD through the same `OkHttpClient` opens a QUIC connection Cronet can reuse:

```kotlin
client.newCall(
    Request.Builder().url("https://api.example.com/").head().build(),
).execute().close()
```

Pair that HEAD with `addQuicHint` so the first connection tries HTTP/3.

Priority is the existing mapper. One function sees every OkHttp caller:

```kotlin
SarieBridge.install(
    context,
    SarieConfig {
        certificatePinner(client.certificatePinner)
        mapper { request, builder ->
            builder.setPriority(priorityFor(request))
        }
    },
)
```

`configure` must not call `addPublicKeyPins`. Cronet only appends pins, and Sarie cannot remove pins `configure` already added. Sarie still appends the OkHttp client's pins after `configure`.

#### Opting out individual requests
If a specific request must run on stock OkHttp, tag it with `CronetOptOut`:

```kotlin
val request = Request.Builder()
    .url("https://api.example.com/special")
    .tag(CronetOptOut::class.java, CronetOptOut)
    .build()
```

#### Emergency runtime kill switch
Disable Cronet routing immediately across the entire process without rebuilding by setting the system property:
```kotlin
System.setProperty("okhttp.cronet.enabled", "false")
```

#### Storage directory and cache clearing
The directory `<cacheDir>/cronet-cache` (or `<cacheDir>/cronet-cache-<suffix>` in secondary processes) holds QUIC and `Alt-Svc` server state for a Sarie-built engine. It can be deleted safely at any time: the next `install` recreates it, and deleting it only drops remembered HTTP/3 state. Android's standard "Clear cache" action clears this directory as well.

---

## What runs where

| Request | Transport |
| --- | --- |
| HTTPS matching policy (default client, allowlisted host) | **Cronet** (HTTP/3 or HTTP/2, as negotiated) |
| Cleartext `http://` or loopback addresses | **Stock OkHttp** |
| WebSockets (`ws://`, `wss://`) | **Stock OkHttp** |
| Client with an OkHttp `Cache` | **Cronet** for allowed HTTPS. A 200 is stored without a TLS block; a hit has `handshake == null`. Cronet's own HTTP cache is off. |
| Client with network interceptors | **Cronet**. They run on OkHttp's chain; the terminal hop is Cronet. `chain.connection()` stays null. |
| Client with a custom `Authenticator` | **Cronet**. A 401 calls `authenticate(route = null, response)`. A 407 on this path throws `ProtocolException`. A proxy still uses **stock OkHttp**. |
| Client with custom Proxy / ProxySelector | **Stock OkHttp** |
| Exact or `**.host` pins on the Sarie-built engine, matching what was installed | **Cronet** (pin failure is `SSLPeerUnverifiedException`, not a stock retry) |
| Borrowed engine, a `*.host` pin, or pins that differ from the installed set | **Stock OkHttp** (`reason=pins`) |
| Client with custom TrustManager / SSLSocketFactory | **Stock OkHttp** |
| Client with `H2_PRIOR_KNOWLEDGE` | **Stock OkHttp** |
| Engine not installed, opt-out tag, or kill switch set to false | **Stock OkHttp** |

*Application interceptors always execute for all requests. Network interceptors also run when the call is allowed onto Cronet.*

---

## Metrics

Pass an optional `SarieListener` to `install`. `onRouted` runs on the caller thread before any I/O. The `reason` is null when the call is going to Cronet, and a `FallbackReason` when it is going to stock OkHttp. The `Call` is the correlation key: tags stay on `call.request()`.

`onFinished` runs on Sarie's listener thread, once per Cronet `UrlRequest` (a transport retry reports twice). The argument is Cronet's `RequestFinishedInfo`: wire `receivedByteCount` and `sentByteCount`, DNS / connect / SSL / TTFB timestamps, `socketReused`, and the failure. Cronet has already decoded the body, so `receivedByteCount` is smaller than the string you read. A provider that rejects `setRequestFinishedListener` (some HttpEngine builds) skips `onFinished` for that engine.

There are no process-wide counters. Count `onRouted` and `onFinished` in the listener if you need a total.

```kotlin
SarieBridge.install(
    context,
    SarieConfig {
        certificatePinner(client.certificatePinner)
        listener(object : SarieListener {
            override fun onRouted(call: Call, reason: FallbackReason?) {
                val operation = call.request().tag(Operation::class)
            }

            override fun onFinished(call: Call, info: RequestFinishedInfo) {
                val wireBytes = info.metrics.receivedByteCount
            }
        })
    },
)
```

---

## Why HTTP/3

Reddit published the result of moving Android feed traffic to HTTP/3: feed failure rate −10.1%, main feed request latency −1.36%, and slow video starts (over 1s) −14.53%. The write-up is [`docs/reddit-journey-to-http3-on-android.md`](docs/reddit-journey-to-http3-on-android.md).

---

## Coming from cronet-okhttp

| Problem on the Cronet path | Sarie |
| --- | --- |
| Request tags dropped | The request stays an OkHttp `Call`. Read tags from `call.request()`. |
| Flipper / network interceptors blind | Network interceptors run before the Cronet hop. |
| Fork and package relocation to A/B | No fork. `SarieListener.onRouted` says which transport and why. `okhttp.cronet.enabled=false` turns it off. |
| Upstream OkHttp contract drift | The plugin's recipe registry fails the build on an unexpected OkHttp shape. |
| Wire bytes, DNS, connect, TTFB | `SarieListener.onFinished` hands over Cronet's `RequestFinishedInfo`. |
| Cold engine on the first request | Call `install` off the main thread early. Calls made before it returns use stock OkHttp and do not wait. |
| Preconnect and request priority | One HEAD through the `OkHttpClient` after `install`, plus `addQuicHint`. Priority is the mapper. |
| Stale DNS | On by default. `configure` can turn it off. |

Sarie does not invent an `InetAddress`, a `Connection`, or a handshake for OkHttp's `EventListener`. It does not show compressed bytes inside a network interceptor, because Cronet decodes the body first. POST 0-RTT is a QUIC limit.

---

## How this project compares with `google/cronet-transport-for-okhttp`

Google provides an official integration in [`google/cronet-transport-for-okhttp`](https://github.com/google/cronet-transport-for-okhttp). While that library served as inspiration, Sarie was designed to address fundamental architectural and operational shortcomings:

### Key differences

1. **Zero-touch integration vs. Fragile interceptor ordering**:
   - `google/cronet-transport-for-okhttp` is an application interceptor (`CronetInterceptor`) or a `Call.Factory` wrapper (`CronetCallFactory`). If used as an interceptor, **it must be the absolute last interceptor** in the chain; any interceptor placed after it is silently skipped. Furthermore, calls made by third-party libraries using their own `OkHttpClient` cannot use Cronet unless each client instance is individually configured.
   - **Sarie** rewrites `ConnectInterceptor` at build time. It sits beneath *all* application interceptors where connections are opened. You do not modify client instances, and every OkHttp call across your app and dependencies automatically benefits from Cronet.

2. **Fail-closed pre-send safety vs. Silent configuration bypass**:
   - `google/cronet-transport-for-okhttp` forces requests through Cronet even when the `OkHttpClient` has configurations Cronet does not support (such as custom proxy selectors, custom SSL socket factories, or OkHttp caches). This **silently bypasses your security and proxy configurations**.
   - **Sarie** evaluates a pre-send policy before starting any request. If an unsupported client configuration is detected, it falls back to stock OkHttp and reports the reason to `SarieListener.onRouted`.

3. **Immediate cancellation vs. Polling lag**:
   - `google/cronet-transport-for-okhttp` checks for call cancellation using a periodic polling loop (~500 ms delay).
   - **Sarie** registers directly with OkHttp's `Call.addEventListener`, delivering cancellation signals to Cronet immediately without polling.

4. **WebSockets and streaming protocols**:
   - `google/cronet-transport-for-okhttp` fails on WebSocket requests.
   - **Sarie** detects WebSocket upgrades and routes them directly to native OkHttp.

5. **Transport-failure retries**:
   - `google/cronet-transport-for-okhttp` treats any Cronet transport failure as fatal.
   - **Sarie** transparently retries pre-header transport failures once for idempotent requests (GET, HEAD, OPTIONS) if `retryOnConnectionFailure` is enabled.

6. **Full request tag support**:
   - `CronetCallFactory` from Google throws an exception if `Request.tag()` is used.
   - **Sarie** preserves all OkHttp request tags.

### Feature comparison matrix

| Feature / Behavior | google/cronet-transport-for-okhttp | Sarie |
| --- | --- | --- |
| **Integration** | Must add `CronetInterceptor` or use `CronetCallFactory` | Build-time bytecode rewrite; zero client modifications |
| **Interceptor placement** | Must be last; reordering silently drops downstream interceptors | Sits below all application interceptors; none are skipped |
| **Unsupported client config** (proxy, unbridged pins, custom trust, …) | Dispatched to Cronet anyway; OkHttp configs silently bypassed | Fail-closed pre-send fallback to stock OkHttp; `SarieListener.onRouted` gets the reason |
| **OkHttp Cache** | Cronet responses never cached | OkHttp's cache stores Cronet responses (no TLS block; a hit has `handshake == null`). Cronet's HTTP cache is off. |
| **WebSocket** | Fails / Unsupported | Transparently routed to stock OkHttp |
| **Cancellation** | ~500 ms poll loop | Immediate, via OkHttp `Call.addEventListener` |
| **Request tags** | Dropped / throws in `CronetCallFactory` | Fully preserved |
| **Transport failure** | Terminal | Retries idempotent calls once before headers |
| **HTTP 407 (Proxy auth)** | Can crash follow-up logic | Clean `IOException`; proxy clients denied pre-send |
| **Request opt-out** | Requires separate OkHttpClient / CallFactory | Per-request via `CronetOptOut` tag |
| **Cronet runtime dependency** | Bundled by host or library | None (`compileOnly` API; host picks Play Services or Embedded) |

---

## HTTP/3 vs HTTP/2 on the Cronet path

When requests run over Cronet, `response.protocol` is set to `Protocol.HTTP_3` or `Protocol.HTTP_2` based on the negotiated transport.

On the Cronet path:
- `EventListener.callEnd` fires when response headers return; the response body streams incrementally.
- `callTimeout` bounds the header phase; streaming body reads are bounded by `readTimeout`.
- The Sarie-built engine advertises `Accept-Encoding: gzip, deflate` (brotli is off) and transparently decodes those responses. A borrowed engine advertises whatever it was built with; Sarie still strips encodings that engine decoded.
- Connection migration and 0-RTT QUIC handshakes are handled internally by Cronet.
- `handshake` stays null, including on a cached HTTPS hit. The bridge does not fabricate a connection or an IP. OkHttp may fill `cacheResponse` and `networkResponse`. See [`COMPATIBILITY.md`](COMPATIBILITY.md).

---

## Documentation & Reference

- [`COMPATIBILITY.md`](COMPATIBILITY.md): Complete behavior contract with test citations for every supported scenario.
