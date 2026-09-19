# Third-party provenance

1. **google/cronet-transport-for-okhttp**
   - Upstream: https://github.com/cronet-transport-for-okhttp/cronet-transport-for-okhttp (Google) @ commit `eda650fbc9b5279b6219160c2a0b210b28303fd7`
   - License: Apache-2.0
   - Use: request/response mapper ported into `bridge/src/main/kotlin/dev/okhttpcronet/bridge/mapping/`;
     ported files keep their Apache-2.0 license headers:
     - `RequestConverter.kt` <- upstream `java/com/google/net/cronet/okhttptransport/RequestResponseConverter.java`
       (+ `RequestBodyConverterImpl.java` for the upload provider)
     - `ResponseConverter.kt` <- upstream `ResponseConverter.java` (incl. the
       `keepEncodingAffectedHeaders` logic)
     - `OkHttpBridgeCallback.kt` <- upstream `OkHttpBridgeRequestCallback.java` (Guava futures
       replaced with plain futures/queues)
     - `UploadDataProviders.kt` <- upstream upload-provider adaptation
   - Not ported: `RedirectStrategy.java` (the bridge never follows redirects inside Cronet).

2. **square/okhttp**
   - Upstream: https://github.com/square/okhttp @ tag `parent-5.5.0`
   - License: Apache-2.0
   - Use: golden bytecode fingerprint source (pre-patch `ConnectInterceptor.class` from the pinned 5.5.0 artifacts).

3. **Chromium Cronet**
   - Upstream: Chromium project (consumed via Google Maven artifacts `org.chromium.net:cronet-api` / `cronet-embedded` 143.7445.0)
   - License: BSD-3-style Chromium license
   - Use: engine runtime (consumed via Google Maven artifacts).

## Per-todo upstream test provenance

### Port test provenance (final)

Which suite tests derive from upstream patterns, and from what:

- **Google bridge testapp pattern (local CA + network security config).** The local
  HTTP/3 origin's TLS setup follows the upstream testapp approach of trusting a locally
  generated CA through Android's network security config rather than a custom OkHttp
  SSL socket factory (a custom factory would itself trigger the trust fallback):
  `scripts/gen-certs.sh` (deterministic CA + leaf, SANs `DNS:localhost, IP:127.0.0.1,
  IP:10.0.2.2`), `sample/src/main/res/raw/caddy_root_ca`,
  `sample/src/main/res/xml/network_security_config.xml`.
- **h3-proof structure.** `CronetSuite.h3NegotiatedAgainstPublicOrigin` /
  `MinifiedSuite.publicOriginH3ThroughTrampoline` follow the structure of Google's
  `CronetHttp3Test` (assert negotiated h3 through the transport against an h3-only
  origin; plan todo 9 reference, structure only) — adapted to the public h3-only origin
  because local-anchor QUIC is engine-blocked (see `COMPATIBILITY.md` row 23).
- **OkHttp semantics tests.** The JVM suite assertions (gzip/br decoding and
  Content-Encoding/Content-Length stripping, 204/205/HEAD body rules, multi-value
  headers, protocol mapping incl. `h3-29` -> `HTTP_3`, streaming reads and byteCount
  limits, readTimeout stall abort, cancel semantics, redirect surfaces-as-3xx, 407
  rejection) port the upstream transport's converter/callback test patterns and verify
  OkHttp `parent-5.5.0` documented semantics against the pinned cronet-api doubles:
  `ResponseConverterTest`, `OkHttpBridgeCallbackTest`, `UploadDataProvidersTest`,
  `CronetBridgeTest`.
- **Device suites (this project's own).** `BaselineSuite`, the remaining `CronetSuite`
  cases, and `MinifiedSuite` are original to this project (policy routing, path metrics,
  kill switch, R8 survival) — no upstream test source.

## Caddy HTTP/3 test origin

- **Caddy 2.11.4** (`caddy_2.11.4_linux_amd64.tar.gz`)
  - Upstream: https://github.com/caddyserver/caddy/releases/tag/v2.11.4
  - License: Apache-2.0
  - sha256 (tarball): `527fbf917c39189a1e3b31d34fa955601680b2d5c8055d2a87b8b9588dec7bb9`
    (cross-checked against the release's official `caddy_2.11.4_checksums.txt`, which lists
    SHA-512 `8220d1f013b6f27510247b2360c9e0ca9f018feebd82515f07635318b34ff9777ccc8fd0b6e6f2486ce3a33fe389fbb7db12d05baa474f4587509fb4f5ebf1c9`)
  - Use: local HTTP/3 (QUIC) test origin for the instrumented suites; downloaded by
    `scripts/download-caddy.sh`, configured by `scripts/Caddyfile`.

## Toolchain pins and verification (2026-09-19)

- **AGP 8.13.0** — verified against Google Maven `com.android.tools.build/gradle` maven-metadata.xml
  (8.13.0 present; newest stable below 9.0.0 is 8.13.2, not used — task pins 8.13.0).
- **Gradle 8.14.3** — wrapper distribution (`gradle-8.14.3-bin`) pinned in root and `plugin-build/`;
  within AGP 8.13's declared compatibility range (requires Gradle 8.13+).
- **JDK Temurin 21.0.12+1 LTS** — `/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`;
  required for every Gradle invocation (`JAVA_HOME`), machine default JDK 26 breaks AGP.
- **Kotlin 2.2.20**, **OkHttp 5.5.0**, **cronet-api / cronet-embedded 143.7445.0**
  (both verified present on Google Maven), **JUnit 4.13.2**, **mockwebserver3 5.5.0**, **ASM 9.7.1**.
- compileSdk 37 (platform dir `android-37.0` on disk), minSdk 24, targetSdk 37 (applies to the
  application module introduced in a later todo).

<!-- golden-bytecode:start -->
## Golden bytecode artifacts
- `com.squareup.okhttp3:okhttp-android:5.5.0` AAR sha256: `6c7fd12f092e64ca2eae0b8a8023900c0d7ffec57888cf8abc8085c6f42e1dcc`
  (source of stock/5.5.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.5.0` JAR sha256: `234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52`
  (source of stock/5.5.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`,
  jvm `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`.
- Regenerate: `JAVA_HOME=<temurin-21> plugin-build/scripts/generate-fingerprints.sh [version ...]`.
<!-- golden-bytecode:end -->
