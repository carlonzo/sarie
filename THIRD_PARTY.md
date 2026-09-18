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
