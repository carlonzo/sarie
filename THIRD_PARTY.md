# Third-party provenance

1. **google/cronet-transport-for-okhttp**
   - Upstream: https://github.com/google/cronet-transport-for-okhttp @ commit `eda650fbc9b5279b6219160c2a0b210b28303fd7`
   - License: Apache-2.0
   - Use: request/response mapper ported in later todos.

2. **square/okhttp**
   - Upstream: https://github.com/square/okhttp @ tag `parent-5.5.0`
   - License: Apache-2.0
   - Use: golden bytecode fingerprint source (pre-patch `ConnectInterceptor.class` from the pinned 5.5.0 artifacts).

3. **Chromium Cronet**
   - Upstream: Chromium project (consumed via Google Maven artifacts `org.chromium.net:cronet-api` / `cronet-embedded` 143.7445.0)
   - License: BSD-3-style Chromium license
   - Use: engine runtime (consumed via Google Maven artifacts).

## Per-todo upstream test provenance (appended during execution)

(to be appended as todos port upstream-derived tests)

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
  (source of stock/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.5.0` JAR sha256: `234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52`
  (source of stock/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`,
  jvm `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`.
- Regenerate: `JAVA_HOME=<temurin-21> plugin-build/scripts/generate-fingerprints.sh`.
<!-- golden-bytecode:end -->
