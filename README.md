# okhttp-cronet-transport

A standalone Android project that lets any OkHttp 5.5.0 app speak HTTP/3 through Cronet
without an OkHttp fork and without a user-visible interceptor: a host-app Gradle plugin
rewrites exactly one OkHttp method at build time so requests first pass through a bridge
that routes allowlisted origins over Cronet and everything else over stock OkHttp.

There is no interceptor to install and no `Call.Factory` to swap: the bridge sits **below**
every interceptor the app adds, at `ConnectInterceptor.intercept`, and a pre-send policy
sends every unsupported configuration back to stock OkHttp before any Cronet I/O starts.
See `COMPATIBILITY.md` for the exact (test-cited) contract and `ROLLBACK.md` for the
opt-out/rollback story.

## 60-second integration

Requirements: an Android **application** module, OkHttp **5.5.0** (pinned and enforced —
see "Toolchain requirements" below), minSdk 24.

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
    implementation("com.squareup.okhttp3:okhttp:5.5.0")   // exact pin, enforced by the plugin guards
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

### 6. Toolchain requirements

- **JDK 21** for building (`JAVA_HOME` must point at a Temurin 21 install; this repo pins
  `/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`).
- **minSdk 24** (bridge and sample), compileSdk 37.
- **OkHttp pinned exactly 5.5.0** — the plugin enforces it at build time on every app
  module: `verifyOkHttpPin` fails the build if a different okhttp resolves on the runtime
  classpath, and `verifyOkHttpFingerprint` fails if the shipped `ConnectInterceptor.class`
  does not match the recorded 5.5.0 bytecode fingerprint. Both run before `preBuild`.
- AGP 8.13.0 / Gradle 8.14.3 / Kotlin 2.2.20 / cronet-api + cronet-embedded 143.7445.0
  (full pins and quirks in the table below and `THIRD_PARTY.md`).

### 7. Verify it (this repo's suites)

Every claim in the docs traces to one of these suites; they are the living documentation.

```bash
export JAVA_HOME=/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS

# JVM: mapping, policy, bridge glue, rewriter + fingerprints + TestKit plugin tests
./gradlew :bridge:testDebugUnitTest :plugin:test

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
Every Gradle invocation needs `JAVA_HOME` set to the Temurin 21 path above.

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
