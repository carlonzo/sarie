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

The switch also governs `CacheHooks`. `SarieBridge.isEnabled()` false makes
`expectTlsBlock` and `requireHandshake` the stock checks, so a Sarie cache entry (no TLS
block) reads as a miss and stock refetches with a handshake. The bridge stays installed
and the engine keeps running. Nothing is torn down.

Evidence: `CronetSuite.killSwitchMissesSarieEntryAndStockRefetchHasHandshake`,
`CacheHooksTest.enabled hooks skip an exhausted tls block and do not require a handshake`.

## 2. Uninstalling the plugin (build-time revert to stock)

Remove `id("com.carlonzo.sarie")` from the application module's `plugins` block
(and the `:bridge` dependency, if you want the classes gone too), then rebuild.

The rewrite is **build-time only**: the plugin transforms exactly the registered targets
(`ConnectInterceptor.intercept` full replace, `CallServerInterceptor.intercept` prefix,
and the two cache `isHttps` sites). Without the plugin, the shipped OkHttp bytecode is
untouched — nothing was ever injected into the published library. Sarie cache entries
already on disk are left behind; stock OkHttp treats them as misses (the stock TLS-block
read fails) and refetches. Removing the `:bridge` dependency as well leaves no `sarie`
classes in the APK at all.

Evidence of the injected shape (and that the fallback inside it is byte-shape identical to
stock OkHttp): `CronetBridgeTest.fallback bytecode shape - initExchange and copy referenced and ConnectInterceptor absent`.

## 3. Fingerprint / pin failure recovery

The plugin guards run on `preBuild` of every application or library module the plugin is
applied to:

- `verifyOkHttpPin` — accepts the supported versions (currently 5.4.0 and 5.5.0), warns
  if a newer untested okhttp is resolved, and fails on anything older (including OkHttp 4).
- `verifyOkHttpFingerprint` — fails if a registered target class (`ConnectInterceptor`,
  `CallServerInterceptor`, `Cache$Entry`, `CacheStrategy$Factory`) inside the
  `okhttp-android` AAR or `okhttp-jvm` jar does not match the recorded SHA-256 fingerprint
  of that supported version. Skipped (with the same untested warning) when the resolved
  version is newer than the supported set.

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

`SarieBridge.install(context, client)` builds an engine and keeps it for the process
lifetime. `SarieBridge.install(engine)` borrows a host-built engine. Either way
`uninstall()` drops only the snapshot reference and does **not** call `shutdown()` or
`stopNetLog()`. Replacing an engine is another `install(...)`. `SarieBridge.uninstall()`
also makes the cache hooks the stock checks, so later reads of Sarie entries miss
(section 1).

The directory `<noBackupFilesDir>/sarie-cronet` holds QUIC / alt-svc state for a
Sarie-built engine. It can be deleted safely: the next `install(context, client)` creates
it again, and losing it only drops remembered HTTP/3 state.

Evidence: `SarieBridgeTest` (borrowed-engine semantics: shutdown counter stays zero
across install/uninstall/replace), `CacheHooksTest.uninstall restores the stock checks`.

## 5. Per-request opt-out

Individual requests can skip Cronet without touching global state:

```kotlin
request.newBuilder().tag(CronetOptOut::class.java, CronetOptOut)
```

Denied pre-send with `reason=tag_opt_out`; the call runs on stock OkHttp.
Evidence: `PolicyEngineTest.CronetOptOut tag yields tag_opt_out`.
