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
