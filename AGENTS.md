# AGENTS.md

Read this first: this repo ships HTTP/3 for OkHttp apps by rewriting one method of OkHttp at
build time and routing allowed requests through Cronet at runtime. Nothing here forks OkHttp
and no user-visible interceptor is added. Before changing anything, read the module AGENTS.md
for the code you touch and `COMPATIBILITY.md` for the behavior contract.

## What it is

HTTP/3 (and Cronet transport generally) for OkHttp 5.x apps. A Gradle plugin rewrites
`okhttp3.internal.connection.ConnectInterceptor.intercept` into a trampoline that calls the
runtime bridge; the bridge decides per request whether Cronet or stock OkHttp handles it.

## Mechanism

- `plugin/` (AGP ASM, `InstrumentationScope.ALL`) rewrites
  `ConnectInterceptor.intercept` into `INVOKESTATIC
  sarie/bridge/CronetBridge.intercept`.
- The bridge re-evaluates policy per request (`PolicyEngine.shouldHandle`): allow goes to the
  Cronet path; deny runs an exact-stock fallback (`initExchange` + `copy(exchange=)` +
  `proceed`, re-implemented because `index` has no getter).
- The host app creates and owns the `CronetEngine` and hands it over via
  `CronetRuntime.install(engine)` (policy and mapper are optional); the bridge only borrows it.
- Build guards `verifyOkHttpPin` and `verifyOkHttpFingerprint` fail closed; a structural
  bytecode guard fails the build on any unexpected `intercept` shape.

## Hard invariants (MUST NOT break)

- Trampoline descriptor stays `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;` targeting
  `sarie/bridge/CronetBridge`.
- The bridge must never call `ConnectInterceptor.INSTANCE.intercept`; that recurses into
  itself. The fallback re-implements the stock body instead.
- No cross-engine retry after a Cronet request has started.
- OkHttp versions are governed by the recipe registry in the plugin (see
  `plugin/AGENTS.md`); never bypass it.
- No fabricated `Response` metadata: `handshake`, `networkResponse` stay unset.
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
