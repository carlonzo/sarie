# R8 rules for the minifiedRelease test variant (todo 10).
#
# The sample's manifest is an empty <manifest /> (no launcher activity): nothing in main
# sources is reachable from manifest entry points, so R8 strips anything not pulled in by
# an explicit keep. The trampoline (INVOKESTATIC CronetBridge.intercept injected into
# ConnectInterceptor by the okhttp.cronet plugin) is only a live root if okhttp itself
# survives: nothing reachable ever constructed an OkHttpClient, so the first R8 run
# stripped ALL of okhttp - trampoline included - and the whole bridge with it
# (usage.txt: sarie.bridge.CronetBridge removed). Re-rooting the graph at the
# entry instrumentation drives fixes reachability for everything downstream. NO wildcard
# keep of sarie.bridge.** and NO blanket okhttp3.internal.** keep: internals
# (ConnectInterceptor, RealInterceptorChain, RealCall, ...) stay shrinkable/renamable -
# renaming is exactly what this suite must survive.

# (1) Reachability root for the transport under test. The sample has NO call sites (the
# instrumented suites make every call), so R8's view of the app is "OkHttpClient exists,
# nothing is ever invoked": keeping only OkHttpClient kept newCall's body but stripped the
# never-invoked downstream - RealCall.execute/getResponseWithInterceptorChain, every
# built-in interceptor, the trampolined ConnectInterceptor, and with them the whole bridge
# (first run: usage.txt removed ConnectInterceptor + CronetBridge, zero inline
# attribution). Keeping the public API package roots exactly the surface the suites
# drive: Call/WebSocket dispatch reaches RealCall -> ConnectInterceptor (ASM trampoline)
# -> CronetBridge -> bridge runtime + Metrics + PolicyEngine. okhttp3.internal.** is NOT
# kept: internals stay shrinkable/renamable - renaming is what this suite must survive.
-keep class okhttp3.* { *; }

# (1b) okhttp/okio internals the THIN test APK links by name: mockwebserver3's HTTP/1.1 +
# HTTP/2 + WebSocket server engine (packaged in the androidTest APK) calls into
# okhttp3.internal.** and okio.Okio directly. Two failure shapes observed on device:
#   - class merged away (no mapping class line -> applymapping cannot rewrite the test
#     ref -> NoClassDefFoundError): TaskRunner$RealBackend (run10: MockWebServer.<init>),
#     Util/_UtilJvmKt facade, Okio facade, Platform/ErrorCode/WebSocketExtensions
#     companions;
#   - member inlined into a caller (no mapping member line -> NoSuchMethodError):
#     Util.threadFactory at MockWebServer.<init> (run11).
# Full keeps pin exactly the server-engine-linked surface; the trampoline path
# (ConnectInterceptor/RealCall/RealInterceptorChain/RealConnection/codecs) is NOT in this
# list and stays fully shrinkable/renamable. Pinning by name also stabilizes the
# cross-APK mapping pairing across rebuilds.
-keep class okhttp3.internal.Internal { *; }
-keep class okhttp3.internal.Util { *; }
-keep class okhttp3.internal.concurrent.TaskRunner { *; }
-keep class okhttp3.internal.concurrent.TaskRunner$Backend { *; }
-keep class okhttp3.internal.concurrent.TaskRunner$RealBackend { *; }
-keep class okhttp3.internal.concurrent.TaskQueue { *; }
-keep class okhttp3.internal.connection.BufferedSocket { *; }
-keep class okhttp3.internal.http.HttpMethod { *; }
-keep class okhttp3.internal.http2.ErrorCode { *; }
-keep class okhttp3.internal.http2.ErrorCode$Companion { *; }
-keep class okhttp3.internal.http2.Header { *; }
-keep class okhttp3.internal.http2.Http2Connection { *; }
-keep class okhttp3.internal.http2.Http2Connection$Builder { *; }
-keep class okhttp3.internal.http2.Http2Connection$Listener { *; }
-keep class okhttp3.internal.http2.Http2Stream { *; }
-keep class okhttp3.internal.http2.Settings { *; }
-keep class okhttp3.internal.platform.Platform { *; }
-keep class okhttp3.internal.platform.Platform$Companion { *; }
-keep class okhttp3.internal.ws.RealWebSocket { *; }
-keep class okhttp3.internal.ws.WebSocketExtensions { *; }
-keep class okhttp3.internal.ws.WebSocketExtensions$Companion { *; }
-keep class okhttp3.internal.ws.WebSocketProtocol { *; }
-keep class okio.Okio { *; }
# (1b2) okio's public API is the second half of the thin test APK's transport surface
# (mockwebserver3 + the suites drive Buffer/BufferedSource/Okio directly): R8 inlined
# app-side members away (okio.Buffer.writeUtf8 -> gone; MockResponse.Builder.body died
# with NoSuchMethodError V(Ljava/lang/String;)Lf0/j;, run13), and okio 3.18's
# BufferedSource.read([B)I vanished with it. Same shape as rule (1): pin the public
# package, okio.internal.** stays shrinkable/renamable.
-keep class okio.* { *; }

# (1c) Kotlin multi-file part classes have no facade: mockwebserver3 (Kotlin) calls
# okhttp3.internal._UtilJvmKt members directly, and R8 inlined these three into their
# app-side callers (no mapping member line -> NoSuchMethodError at MockWebServer.<init>,
# run11/run12: Util.threadFactory). keepclassmembers blocks the inlining-removal; the
# member may still be renamed (applymapping rewrites the test ref).
-keepclassmembers class okhttp3.internal._UtilJvmKt {
    public static java.util.concurrent.ThreadFactory threadFactory(java.lang.String, boolean);
    public static void closeQuietly(java.net.ServerSocket);
    public static java.util.List immutableListOf(java.lang.Object[]);
}

# (2) Test-facing bridge surface. The androidTest R8 run applies this APK's mapping
# (-applymapping), so test-side references are rewritten to the final names - but ONLY
# if the mapping has a CLASS line: when R8 vertically merges a class (its methods inline
# into the merge target and the class line disappears), the test refs stay under the
# original name and die with NoClassDefFoundError (SarieBridge, run9: merged away once
# the kotlin.** keep below shifted R8's merging decisions). Full keeps pin the classes
# the suites drive; the rest of the bridge (CronetBridge, PolicyEngine, converters, ...)
# stays shrinkable/renamable.
#   SampleAppRuntime - every suite's install/lastEngine entry point (also stripped
#       outright without a keep: nothing in main references it)
#   SarieBridge - install/uninstall/snapshot
#   Metrics + Metrics$Reason - resetForTest/getCronet/getOkhttpFallback/getLastReason +
#       the reason constants every path assertion compares
#   RuntimeSnapshot.getPolicy() / CronetPolicy.enabled() - BaselineSuite kill-switch probe
-keep class sarie.sample.SampleAppRuntime { *; }
-keep class sarie.bridge.SarieBridge { *; }
-keep class sarie.bridge.Metrics { *; }
-keep class sarie.bridge.Metrics$Reason { *; }
-keep class sarie.bridge.RuntimeSnapshot { *; }
-keep class sarie.bridge.CronetPolicy { *; }
-keepclassmembers class org.chromium.net.CronetEngine {
    public void shutdown();
    public void stopNetLog();
}

# (3) Cross-APK ABI, runner support libraries: AGP's androidTest R8 run dedupes every
# class the tested variant's runtime classpath also provides to LIBRARY-side (packaged
# only in the app APK), yet keeps the test-side references under their original names
# when the app mapping has no entry. The app R8 run strips these - unreachable from any
# app root - so instrumentation dies on first use:
#   kotlin.** - the test APK is thin: kotlin-stdlib is deduped to this APK, and the
#       test-packaged code (kotlinx-coroutines, junit, mockwebserver3, androidx.test)
#       uses stdlib internals far beyond what this app's own code reaches. First crash:
#       NoClassDefFoundError kotlin.jvm.internal.Lambda at FileTestStorage.<init> (the
#       test APK ships kotlinc-compiled lambda classes extending it; the app's own
#       lambdas are all R8-desugared synthetics, so R8 had stripped Lambda). Then
#       androidx.tracing.Trace at AndroidJUnitRunner.onCreate, then
#       androidx.concurrent.futures.* / androidx.lifecycle.Lifecycle$State from
#       monitor/runner code paths.
# All of these artifacts are on the app runtime classpath, so the app can provide them.
-keep class kotlin.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class androidx.lifecycle.** { *; }

# (4) The androidTest APK is minified by AGP (minifyMinifiedReleaseAndroidTestWithR8) and
# its generated config carries no -keepattributes for runtime annotations: without this
# rule R8 strips JUnit's runtime-visible @Test/@Before/@After from the suite classes and
# the runner discovers 0 tests ("Starting 0 tests", run3 log). This file is fed to the
# androidTest R8 task via variant.androidTest.proguardFiles.
-keepattributes *Annotation*

# (5) androidx.test internals reference compile-time-only errorprone annotations that are
# not on any runtime classpath; the androidTest APK minification fails without these
# -dontwarns (missing_rules.txt as generated by AGP).
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
