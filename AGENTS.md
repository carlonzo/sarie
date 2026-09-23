# AGENTS.md

Read this first: this repo ships HTTP/3 for OkHttp apps by rewriting four registered OkHttp
call sites at build time and routing allowed requests through Cronet at runtime. Nothing here
forks OkHttp and no user-visible interceptor is added. Before changing anything, read the
module AGENTS.md for the code you touch and `COMPATIBILITY.md` for the behavior contract.

## What it is

HTTP/3 (and Cronet transport generally) for OkHttp 5.x apps. A Gradle plugin rewrites exactly
the registered OkHttp targets into calls to the runtime bridge; the bridge decides per request
whether Cronet or stock OkHttp handles it. Behavior is `COMPATIBILITY.md` — do not duplicate it.

## Mechanism

- `plugin/` (AGP ASM, `InstrumentationScope.ALL`) rewrites exactly the registered targets.
  `ConnectInterceptor.intercept` is a full replace. `CallServerInterceptor.intercept` is a
  prefix. The two cache sites each replace one `isHttps`. Descriptors are in the hard
  invariants below and in `plugin/AGENTS.md`.
- The bridge re-evaluates policy per request (`PolicyEngine.shouldHandle`). Allow registers a
  cycle and `proceed`s with no exchange (network interceptors run); `CronetBridge.callServer`
  runs the Cronet path. Deny is still `initExchange` + `copy(exchange=)` + `proceed`
  (`index` has no getter). `callServer` returns null when `exchange != null` so the stock
  body runs.
- The host may call `SarieBridge.install(context, client)`, which builds the engine.
  `install(engine)` remains the borrowed path. The bridge never calls `shutdown()`.
- Build guards `verifyOkHttpPin` and `verifyOkHttpFingerprint` fail closed; a structural
  bytecode guard fails the build on any unexpected target shape.

## Hard invariants (MUST NOT break)

- Exactly four sites, descriptors exact:
  - `CronetBridge.intercept` `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;` —
    `ConnectInterceptor.intercept` is a full replace.
  - `CronetBridge.callServer` `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;` —
    `CallServerInterceptor.intercept` is a prefix.
  - `CacheHooks.expectTlsBlock` `(Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z` — replaces the
    one `HttpUrl.isHttps` in `Cache$Entry.<init>(Source)`.
  - `CacheHooks.requireHandshake` `(Lokhttp3/Request;)Z` — replaces the one `Request.isHttps`
    in `CacheStrategy$Factory.computeCandidate`.
- The bridge must never call `ConnectInterceptor.INSTANCE.intercept`; that recurses into
  itself. The deny path re-implements the stock body instead.
- No cross-engine retry after a Cronet request has started.
- OkHttp versions are governed by the recipe registry in the plugin (see
  `plugin/AGENTS.md`); never bypass it.
- No fabricated handshake, connection, or IP. `handshake` stays null on the bridge-built
  response and on a cached HTTPS hit. See `COMPATIBILITY.md`.
- Callbacks are CPU-only: `RequestConverter` uses `allowDirectExecutor()`, so Cronet invokes
  `OkHttpBridgeCallback` directly on its own threads. Never block inside a callback.

## Build and test

- `./gradlew build` works as-is; `gradle.properties` pins the JDK via `org.gradle.java.home`
  (temurin-21). The machine default JDK 26 breaks AGP; do not remove the pin.
- Publish with vanniktech (`com.vanniktech.maven.publish` 0.35.0): group
  `com.carlonzo.sarie`, artifacts `plugin` and `bridge`. Credentials and GPG live in
  `~/.gradle/gradle.properties` (`mavenCentralUsername`, `mavenCentralPassword`,
  `signAllPublications=true`, signing key). `./gradlew publishToMavenLocal` publishes both.
- Suites: `./gradlew -p plugin test` (bytecode rewriting + guards),
  `:bridge:testDebugUnitTest` (bridge logic), `:sample:connectedDebugAndroidTest` and
  `:sample:connectedMinifiedReleaseAndroidTest` (device; the Gradle `startTestOrigin` task
  runs the Caddy HTTP/3 origin in `scripts/`). PR CI runs both connected suites on an
  API-30 emulator for every supported okhttp version. The connected suites are the living
  documentation of behavior.

## Module map

- `plugin/`: build-time bytecode rewriting and fail-closed guards. Published as
  `com.carlonzo.sarie:plugin`. See `plugin/AGENTS.md`.
- `bridge/`: runtime Cronet transport and routing policy. Published as
  `com.carlonzo.sarie:bridge`. See `bridge/AGENTS.md`.
- `sample/`: androidTest host; its suites (`BaselineSuite`, `CronetSuite`, `MinifiedSuite`)
  are the behavior contract in executable form. No AGENTS.md; see `sample/build.gradle.kts`.

## Where truth lives

- `COMPATIBILITY.md`: behavior contract, every row test-cited. Reference it; do not duplicate.
- `ROLLBACK.md`: kill switch and recovery paths.
