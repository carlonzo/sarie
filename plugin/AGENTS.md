# plugin/AGENTS.md

Read this first: this included build (own `settings.gradle.kts` and `gradlew`) contains the
Gradle plugin that rewrites exactly one OkHttp method at build time and fails closed on
anything unexpected. Product code lives in `src/main/kotlin/dev/okhttpcronet/plugin/`.
Published as `com.carlonzo.sarie:plugin` (plugin id `com.carlonzo.sarie`).

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

1. `RecordingMethodVisitor` captures the stock `intercept` instruction stream as javap-style
   strings (labels, frames, line numbers are dropped).
2. `GuardSpec.verify` (from `RecipeRegistry.forVersion` / `familyGuard`) checks the pinned
   stock shape: Kotlin null-check preamble, one `CHECKCAST` to `RealInterceptorChain` in the
   first 5 instructions, `initExchange$okhttp` / `copy$okhttp$default` / `proceed` each exactly
   once, ending in `ARETURN`.
3. Pass: `ConnectInterceptorRewriter.emitTrampoline` emits `ALOAD 1` / `INVOKESTATIC
   dev/okhttpcronet/bridge/CronetBridge.intercept` / `ARETURN` with `COMPUTE_FRAMES`.
4. Fail: `IllegalStateException` with the problem list and a javap-style dump of the captured
   instructions. `visitEnd` also fails if `intercept` was never seen.
5. `isInstrumentable` is EXCLUSIVE to `okhttp3.internal.connection.ConnectInterceptor`.
   `<clinit>`, `INSTANCE` and the constructor pass through untouched.

## Guards

- `VerifyOkHttpPinTask` (`verifyOkHttpPin`): resolves the okhttp version from the runtime
  classpaths and applies `RecipeRegistry.decide`. Current behavior: verified versions in
  `RecipeRegistry.recipes` (today 5.4.0 and 5.5.0) pass; versions newer than the oldest
  supported one are UNTESTED and only warn (structural guard still applies, fingerprint check
  skipped); anything older, including okhttp 4, plus missing or mixed versions fail the build.
- `VerifyOkHttpFingerprintTask` (`verifyOkHttpFingerprint`): SHA-256-checks
  `ConnectInterceptor.class` inside the recipe's `okhttp-android` AAR and `okhttp-jvm` jar
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

Fixtures live in `src/test/fixtures/sample-app` (application + plugin, pins okhttp 5.5.0)
and `src/test/fixtures/sample-lib` (library + plugin). Scenarios in `TransportPluginTest`:
happy app build with both guards running, library apply that is not a no-op, and okhttp 4
failing as unsupported. TestKit needs AGP on the `pluginUnderTestMetadata` classpath; that
wiring already exists in `build.gradle.kts`. TestKit forks must use temurin-21 (see the
JDK note in that file).

## Invariants

- Never instrument any class other than `ConnectInterceptor`.
- Never touch `<clinit>`, `INSTANCE`, or the constructor.
- Goldens under `plugin/src/test/resources/stock/` are script-generated; never hand-edit them.
