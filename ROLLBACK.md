# Rollback and upgrade

How to get back to stock OkHttp behavior, at runtime or at build time, and how to recover
from a pinned-version failure. Every mechanism here is exercised by a committed test.

## 1. Runtime kill switch (no rebuild, no redeploy)

Set the system property `okhttp.cronet.enabled=false` (default `true`) — e.g. in the app
process before traffic starts, or via a debug entry point — and every call routes to stock
OkHttp regardless of policy and allowlist. A policy can gate itself independently via
`DefaultPolicy(enabled = { ... })`; either gate off is sufficient.

Evidence: `BaselineSuite.killSwitchRestoresStock`, `CronetSuite.killSwitchRoutesStock`,
`MinifiedSuite.killSwitchRestoresStock` (device, both normal and R8-minified builds),
`PolicyEngineTest.kill switch off yields disabled`,
`PolicyEngineTest.policy disabled yields disabled`.

The switch flips routing only: the bridge stays installed and the engine keeps running.
Nothing is torn down.

## 2. Uninstalling the plugin (build-time revert to stock)

Remove `id("com.carlonzo.sarie")` from the application module's `plugins` block
(and the `:bridge` dependency, if you want the classes gone too), then rebuild.

The rewrite is **build-time only**: the plugin transforms exactly
`okhttp3.internal.connection.ConnectInterceptor.intercept` into a static call to
`CronetBridge.intercept` inside the produced artifact. Without the plugin, the shipped
OkHttp bytecode is untouched — no bridge reference remains in `ConnectInterceptor` because
nothing was ever injected into the published library. Removing the `:bridge` dependency as
well leaves no `dev.okhttpcronet` classes in the APK at all.

Evidence of the injected shape (and that the fallback inside it is byte-shape identical to
stock OkHttp): `CronetBridgeTest.fallback bytecode shape - initExchange and copy referenced and ConnectInterceptor absent`.

## 3. Fingerprint / pin failure recovery

The plugin guards run on `preBuild` of every app module:

- `verifyOkHttpPin` — accepts the supported versions (currently 5.4.0 and 5.5.0), warns
  if a newer untested okhttp is resolved, and fails on anything older (including OkHttp 4).
- `verifyOkHttpFingerprint` — fails if the `ConnectInterceptor.class` inside the
  `okhttp-android` AAR does not match the recorded SHA-256 fingerprint of that supported
  version. Skipped (with the same untested warning) when the resolved version is newer
  than the supported set.

Recovery when OkHttp changes:

1. Decide deliberately to support the new version; this project has **not** verified it.
2. If internals still match, add the version to `RecipeRegistry`,
   `.github/workflows/pr.yml`, and the table in `COMPATIBILITY.md` "OkHttp versions"
   (a minor of this library). If internals broke, raise `okhttpMin` / drop the old
   version from that table and keep the previous library line published so hosts who
   cannot bump OkHttp stay there; patch that old line for bugfixes.
3. Re-run `JAVA_HOME=<temurin-21> plugin/scripts/generate-fingerprints.sh` to refresh
   the fingerprint constants and the goldens.
4. Re-run the full verification stack against every remaining supported version
   (`./gradlew :bridge:testDebugUnitTest :sample:assembleDebug -PokhttpVersion=<v>`,
   `:plugin:test`, device suites on lowest + highest) — the contract in
   `COMPATIBILITY.md` must be re-proven on the new bytecode, not assumed.

Until step 2–4 are done, a newer okhttp is UNTESTED: the build warns and still applies
the structural guard (which hard-fails on shape drift). Older okhttp, including every
4.x release, is not supported and fails the pin task.

## 4. Engine ownership

The engine is **borrowed, never owned**: `CronetRuntime.install` stores a reference to the
host's ready engine, `uninstall()` drops only that reference, and the bridge never calls
`engine.shutdown()` or `stopNetLog()` — stopping (or not stopping) the engine is entirely
the host's decision. Replacing an engine is a plain `install(...)` overwrite of the
snapshot reference.

Evidence: `CronetRuntimeTest` (borrowed-engine semantics: shutdown counter stays zero
across install/uninstall/replace).

## 5. Per-request opt-out

Individual requests can skip Cronet without touching global state:

```kotlin
request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut)
```

Denied pre-send with `reason=tag_opt_out`; the call runs on stock OkHttp.
Evidence: `PolicyEngineTest.CronetOptOut tag yields tag_opt_out`.
