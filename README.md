# okhttp-cronet-transport

A standalone Android project that lets any OkHttp 5.5.0 app speak HTTP/3 through Cronet
without an OkHttp fork and without a user-visible interceptor: a host-app Gradle plugin
rewrites exactly one OkHttp method at build time so requests first pass through a bridge
that routes allowlisted origins over Cronet and everything else over stock OkHttp.

Status: scaffolding — no bridge or plugin logic yet, only module skeletons and pins.

## Pinned toolchain (verified 2026-09-19)

| Component | Pin | Source |
| --- | --- | --- |
| JDK | Temurin 21.0.12+1 LTS (`/home/carlo/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`) | mise, verified on disk |
| AGP | 8.13.0 | Google Maven metadata (8.13.0 exists; 8.13.1/8.13.2 also published, not used) |
| Gradle | 8.14.3 (wrapper, `-bin` dist) | wrapper pinned in root + plugin-build |
| Kotlin | 2.2.20 | plugin-build `kotlin("jvm")` + catalog `kotlin-android` |
| OkHttp | 5.5.0 (`com.squareup.okhttp3:okhttp`) | catalog |
| Cronet | cronet-api / cronet-embedded 143.7445.0 | catalog |
| compileSdk / minSdk | 37 / 24 | module DSL (`compileSdkVersion = "android-37.0"` — see note below) |

Note: `local.properties` is required (not committed); it points at `/home/carlo/Android/Sdk`.
Every Gradle invocation needs `JAVA_HOME` set to the Temurin 21 path above.

Toolchain notes (recorded deviations/quirks):
- The installed platform is the minor-release `android-37.0` (ApiLevel=37.0). Integer
  `compileSdk = 37` makes AGP 8.13.0 look up hash string `android-37` and fail to find it,
  so modules set the string form `compileSdkVersion = "android-37.0"` instead.
- AGP 8.13.0 is tested up to compileSdk 36.1; `android.suppressUnsupportedCompileSdk=37.0`
  is set in `gradle.properties` to silence the recommended-update warning.
- `android.useAndroidX=true` is required: `com.squareup.okhttp3:okhttp:5.5.0` resolves to
  `okhttp-android:5.5.0` on Android, which pulls `androidx.annotation` / `androidx.startup`.
