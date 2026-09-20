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

- **static-curl 8.22.0** (`curl-linux-x86_64-musl-8.22.0.tar.xz`)
  - Upstream: https://github.com/stunnel/static-curl/releases/tag/8.22.0
  - License: curl (ISC-style) plus bundled OpenSSL/zlib/nghttp3/ngtcp2 (see the tarball)
  - sha256 (tarball): `dfb02460ba2abe513087538f12a3cf79b74b64a5ea3787ce8ac0cdb11251f884`
  - Use: host-side `--http3-only` probe of the local Caddy origin so
    `verifyH3ServerEvidence` has access-log `"proto":"HTTP/3"` entries. The emulator
    cannot complete local-origin h3 (Chromium known-root policy). Downloaded by
    `scripts/download-curl-http3.sh`.

## Toolchain pins and verification (2026-09-19)

- **AGP 9.4.1** — catalog pin (`agp`); TestKit injects the same artifact via `libs.agp`.
- **Gradle 9.7.1** — wrapper distribution (`gradle-9.7.1-bin`) pinned in root and `plugin/`;
  AGP 9.4 requires Gradle 9.6+.
- **Kotlin 2.4.20**, **OkHttp 5.5.0**, **cronet-api / cronet-embedded 143.7445.0**
  (both verified present on Google Maven), **JUnit 4.13.2**, **mockwebserver3 5.5.0**, **ASM 9.7.1**.
- compileSdk 37, minSdk 24 (catalog pins).

<!-- golden-bytecode:start -->
## Golden bytecode artifacts
- `com.squareup.okhttp3:okhttp-android:5.0.0` AAR sha256: `cb705383a2a847d4d232fe6bbd325fca50b9e1f680f9d3e38cc49666b66390b0`
  (source of stock/5.0.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.0.0` JAR sha256: `4d6ff6aea0fc7c6b8182d26340216040af0e2186e317305d899f8d324faa8a4a`
  (source of stock/5.0.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.1.0` AAR sha256: `acb328a30b8609b34d9bcaf9a147d72e2279b55f6b22540aa45e0f1441e1fff3`
  (source of stock/5.1.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.1.0` JAR sha256: `a6aac7f15d3c2c3cbd2af7ecf56f97407164a604b2db879529950e31cea699b2`
  (source of stock/5.1.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.2.0` AAR sha256: `2f891880c83481e0edd6add3866c439b3b9dac597a87eb6975c3081e9ba03ff0`
  (source of stock/5.2.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.2.0` JAR sha256: `5b7e7058eb0d02730b86109655947933f4786503a151b3781375904198c7645a`
  (source of stock/5.2.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.2.1` AAR sha256: `1043f2bb063655041f96efe203323a970e33ca839baed2d94190f61fe6e602d8`
  (source of stock/5.2.1/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.2.1` JAR sha256: `9632c08567dbb21c569b13d793107834c8580d44e4eea74b2eae0722f0506179`
  (source of stock/5.2.1/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.2.2` AAR sha256: `5b096ce812e5eb626958e1515e859c8f24f352df6f12b409064a72135b93ce0d`
  (source of stock/5.2.2/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.2.2` JAR sha256: `4b923a4b0493c9c8c09335c624a25ac71fb616279a0afac4b28efd07cede8a38`
  (source of stock/5.2.2/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.2.3` AAR sha256: `f90959fb089e5014dd8772fde80316c263d1367bd63250818dee80a5ec667034`
  (source of stock/5.2.3/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.2.3` JAR sha256: `68927bd7f203d214ff62ebe5e80a8bea8de29dcea321ebf90989b371a9969195`
  (source of stock/5.2.3/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.3.0` AAR sha256: `77f6237a2919c1c58ff8e05ba0275cb7104f0158701809d27ddc6a875753d7f7`
  (source of stock/5.3.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.3.0` JAR sha256: `7d6033eea0eb0045615e36bd5a250f2e0320885ffedb99d5c27a85e6f5f2f8ec`
  (source of stock/5.3.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.3.1` AAR sha256: `fd5ae1a466aa5572ef8740e067d5c6bacab0a94d1bcfc55d83db193b6392f936`
  (source of stock/5.3.1/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.3.1` JAR sha256: `f3007d01d64bd724804a4779a052e49a26ac74a22c1086e4892d01d2f0599ca0`
  (source of stock/5.3.1/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.3.2` AAR sha256: `f1a78812ebeef4d45eee0dbeb4f27347210f564d2b8ee411f012ce8573b0a658`
  (source of stock/5.3.2/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.3.2` JAR sha256: `c771f48075b763f6c322055e21129a94b7de4c1faf3f8f58ace320b0c9517a30`
  (source of stock/5.3.2/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e`,
  jvm `e0840705f2836f0c9f220f8b256a56a2b2c4562bfb171d1dae1875443e95781e` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.4.0` AAR sha256: `0e9cbf6c7c373a5f0b0cde7f773185eb69b485faa19f76bcfab3310badbda901`
  (source of stock/5.4.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.4.0` JAR sha256: `b15e605ca0a4b12c62ea7f657dad413c236c947e875854767349cdbe20df473c`
  (source of stock/5.4.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`,
  jvm `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527` (shape check: yes).
- `com.squareup.okhttp3:okhttp-android:5.5.0` AAR sha256: `6c7fd12f092e64ca2eae0b8a8023900c0d7ffec57888cf8abc8085c6f42e1dcc`
  (source of stock/5.5.0/android golden).
- `com.squareup.okhttp3:okhttp-jvm:5.5.0` JAR sha256: `234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52`
  (source of stock/5.5.0/jvm golden).
- `okhttp3/internal/connection/ConnectInterceptor.class` sha256: android `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527`,
  jvm `bd324e22d23fd4a71896d116a745d37bdaa36a14e4a162cb808ac7b524c5e527` (shape check: yes).
- Regenerate: `JAVA_HOME=<temurin-21> plugin/scripts/generate-fingerprints.sh [version ...]`.
<!-- golden-bytecode:end -->
