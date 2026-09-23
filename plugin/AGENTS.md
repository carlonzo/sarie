# plugin/AGENTS.md

Read this first: this included build (own `settings.gradle.kts` and `gradlew`) contains the
Gradle plugin that rewrites exactly the registered OkHttp targets at build time and fails
closed on anything unexpected. Product code lives in `src/main/kotlin/sarie/plugin/`.
Published as `com.carlonzo.sarie:plugin` (plugin id `com.carlonzo.sarie`). What those
rewrites do at runtime is `../COMPATIBILITY.md`; do not duplicate it here.

## Registration

`TransportPlugin` applies to `com.android.application` and `com.android.library`.
Outside those it is a no-op with a warning.

On **application** modules it hooks `androidComponents.onVariants` and registers
`transformClassesWith(ConnectInterceptorVisitorFactory, InstrumentationScope.ALL)` plus
`FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. AGP forbids
`InstrumentationScope.ALL` on libraries (instrumenting a dependency AAR has no effect on
consumers), so a library apply registers only the pin/fingerprint guards and logs that
the rewrite still needs the plugin on the application that packages the APK. OkHttp
declared only in a library is still rewritten there as a transitive class.

The visitor factory's parameters are `OkhttpCronetInstrumentationParams` with
`okhttpVersion: Property<String>` and optional `invalidateToken: Property<Long>`; only
simple `Property` types cross the AGP instrumentation worker boundary (`okhttpVersion`
defaults to `"family"`, `invalidateToken` can be set via `forceInstrument` or
`-PokhttpCronet.forceInstrument=true` to invalidate AGP's transform cache during
development).

## Rewrite pipeline

`isInstrumentable` accepts exactly the registered targets (`InstrumentationTargets` /
`InstrumentTarget`). Every other class passes through. On a target, only the method below is
rewritten; `<clinit>` and every other method pass through untouched, including
`Cache.Entry.writeTo` (its own `HttpUrl.isHttps`).

1. `ConnectInterceptor.intercept` — full replace. The stock instruction stream is checked
   (Kotlin null-check preamble, one `CHECKCAST` to `RealInterceptorChain` in the first 5
   instructions, `initExchange$okhttp` / `copy$okhttp$default` / `proceed` each exactly once,
   ending in `ARETURN`), then `ConnectInterceptorRewriter.emitTrampoline` emits `ALOAD 1` /
   `INVOKESTATIC sarie/bridge/CronetBridge.intercept (Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`
   / `ARETURN`.
2. `CallServerInterceptor.intercept` — prefix after the Kotlin `checkNotNullParameter`
   preamble: `INVOKESTATIC sarie/bridge/CronetBridge.callServer` with that same descriptor,
   then `DUP` / `IFNULL` into the unchanged stock body.
3. `Cache$Entry.<init>(Lokio/Source;)V` — the one `HttpUrl.isHttps` becomes `aload 6` /
   `INVOKESTATIC sarie/bridge/CacheHooks.expectTlsBlock (Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z`.
4. `CacheStrategy$Factory.computeCandidate` — the one `Request.isHttps` becomes
   `INVOKESTATIC sarie/bridge/CacheHooks.requireHandshake (Lokhttp3/Request;)Z`.

Fail: `IllegalStateException` with the problem list. `visitEnd` also fails if the target
method was never seen. Each target has its own structural guard.

## Guards

- `VerifyOkHttpPinTask` (`verifyOkHttpPin`): resolves the okhttp version from the runtime
  classpaths and applies `RecipeRegistry.decide`. Current behavior: verified versions in
  `RecipeRegistry.recipes` (today 5.4.0 and 5.5.0) pass; versions newer than the oldest
  supported one are UNTESTED and only warn (structural guard still applies, fingerprint check
  skipped); anything older, including okhttp 4, plus missing or mixed versions fail the build.
- `VerifyOkHttpFingerprintTask` (`verifyOkHttpFingerprint`): SHA-256-checks each registered
  target class (`ConnectInterceptor`, `CallServerInterceptor`, `Cache$Entry`,
  `CacheStrategy$Factory`) inside the recipe's `okhttp-android` AAR and `okhttp-jvm` jar
  against `GoldenFingerprints` (script-generated). Mismatch fails unless the host sets
  `allowUnfingerprinted = true`, which downgrades it to a warning; the structural guard stays
  hard either way.
- Both tasks are wired to `preBuild` (the earliest hook every variant depends on).

## Recipe registry

Adding a future OkHttp 5.x version: run
`plugin/scripts/generate-fingerprints.sh <version>` (downloads the artifacts, writes
goldens under `src/test/resources/stock/<version>/`, regenerates
`GoldenFingerprints.kt`, `bridge/.../VerifiedOkHttpVersions.kt`, and the report),
then add one `Recipe` line to `RecipeRegistry.recipes`.
Tests parameterize over the recipes automatically. The structural guard is the safety net:
never weaken it to admit a version.

## TestKit

Fixtures live in `src/test/fixtures/sample-app` (application + plugin) and
`src/test/fixtures/sample-lib` (library + plugin). Both use `implementation(libs.okhttp)`. TestKit copies `gradle/libs.versions.toml` into
the temp project so Gradle's default `libs` catalog loads (do not also `from()` it in
settings — Gradle 9 rejects a second import). Scenarios in `TransportPluginTest`: happy app build with both guards
running, library apply that is not a no-op, and okhttp 4 failing as unsupported (the
catalog `okhttp =` pin is rewritten for that case). TestKit needs AGP on the
`pluginUnderTestMetadata` classpath; that wiring already exists in `build.gradle.kts`.
TestKit forks must use temurin-21 (see the JDK note in that file).

## Invariants

- Instrument exactly the registered targets, and only the four methods in the rewrite pipeline.
- Descriptors stay `CronetBridge.intercept` and `CronetBridge.callServer`
  `(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;`, `CacheHooks.expectTlsBlock`
  `(Lokhttp3/HttpUrl;Lokio/BufferedSource;)Z`, and `CacheHooks.requireHandshake`
  `(Lokhttp3/Request;)Z`. `ConnectInterceptor` stays a full replace. `CallServerInterceptor`
  stays a prefix. The two cache sites each replace one `isHttps`.
- Never touch `<clinit>`, `INSTANCE`, or a constructor other than the one
  `Cache$Entry.<init>(Source)` site.
- Goldens under `plugin/src/test/resources/stock/` are script-generated; never hand-edit them.
