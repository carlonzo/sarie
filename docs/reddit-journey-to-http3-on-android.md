## Reddit's Journey to HTTP/3 on Android (Part 1)

A while back, we set out to move Reddit's GraphQL traffic on Android from our standard OkHttp/HTTP/2 setup over to HTTP/3. On paper, it looked like a clean win: swap the transport underneath, flip a feature flag, and watch latency drop.

It turned out to be much more complex than swapping a library.

In this two-part series, we share the story of our journey to HTTP/3 — why we wanted a unified network engine, how an initial victory with media gave us the confidence to tackle GraphQL, the engineering hurdles we ran into on our most critical code paths, and how we turned a cold-start regression into a production win across hundreds of millions of devices.

The Vision: A Single Engine for All Traffic
HTTP/3 replaces TCP with QUIC, a transport protocol built on top of UDP. For mobile apps, QUIC brings three headline advantages:

Faster connection setup: QUIC folds transport and TLS handshakes together, saving round trips on flaky cellular links.

No TCP head-of-line blocking: In HTTP/2 over TCP, a single lost packet stalls every multiplexed stream on that connection. QUIC streams are independent, so one dropped packet only stalls its own stream.

Connection migration: QUIC connections use IDs rather than IP/port tuples, allowing active requests to survive Wi-Fi ↔ cellular network switches without dropping.

OkHttp doesn't speak HTTP/3 natively on Android, so adopting QUIC required a different stack. While individual libraries could be wired up with their own HTTP/3-capable transports (such as Apollo or Glide), configuring transports piecemeal would leave us with a patchwork of separate HTTP/3 integrations, duplicate connection pools, and fragmented metrics. We wanted the opposite: one shared engine underneath all of our traffic.

That narrowed our choices to two main options:

Cronet: Chromium's network stack, available via Google Play Services or bundled directly into the app.

HttpEngine: The system network client introduced in Android 14+ (API 34).

Beyond HTTP/3: What else Cronet unlocks
Getting to HTTP/3 was the main goal, but a big reason we chose Cronet was the broader feature set that comes with it. OkHttp is a great HTTP client, but Cronet ships with capabilities that are difficult or impossible to build on OkHttp alone:

Stale DNS: On OkHttp, DNS resolution sits on the request's critical path. On mobile, slow resolvers cause tail latency and failures. Cronet's resolver can serve a cached (even expired) answer immediately and refresh in the background, taking DNS off the hot path — which matters when the first few requests decide how fast the feed appears.

Request prioritization: The app has many requests in flight — feed, images, videos, analytics — competing for bandwidth. OkHttp has no shared notion of priority across callers, so prefetched images can crowd out the home feed query. Cronet supports native request prioritization, allowing us to tell the transport that the home feed query matters more than a pre-fetched avatar.

0-RTT connection resumption: QUIC's 0-RTT lets returning clients send data in the very first packet, removing a full round trip from connection setup on startup-critical queries.

Connection migration: On TCP, a connection is pinned to an IP/port pair, so switching networks breaks it and forces in-flight requests to restart. QUIC uses connection IDs instead, allowing Cronet to carry live connections across network switches mid-scroll.

For a small app, these optimizations might be subtle. But at Reddit's scale — hundreds of millions of users on every device and network type, firing billions of requests a day — shaving milliseconds off the critical path and reducing failure rates compounds into a noticeable improvement in app responsiveness and reliability.

Act I: Testing the Waters with Media
We didn't jump straight to GraphQL. We first rolled out Cronet for media — images and video — in partnership with our Media Foundation team. Media was a much simpler starting point for two reasons:

Simpler network path: Image and video requests go directly to the CDN through a minimal interceptor stack, unlike GraphQL's deep application and network interceptors.

Direct last-mile benefits: Media is served straight from CDNs with minimal backend latency, so protocol wins (faster handshakes, no head-of-line blocking) show up clearly in metrics.

The media rollout was an immediate success. Image loading showed solid gains:

p90 image load time: −3.57%

Image success rate: +0.04% (meaningful at scale)

Post views: +0.69%

Video playback saw similar improvements:

Fast video starts (≤500ms): +1.37%

Slow video starts (>1s): −14.53%

Exits before playback: −14.99%

Media gave us confidence in HTTP/3, but moving GraphQL was always going to be a bigger undertaking. Because images and video load asynchronously after the app opens, Cronet's engine initialization overhead remained invisible — until we pointed Cronet at GraphQL, which sits squarely on the cold-start critical path.

Act II: Pointing Cronet at GraphQL
We knew moving GraphQL over to HTTP/3 would be a tougher challenge. It touches our most critical code paths at app launch, and we were already aware of wrapper limitations around request tracking. Even with those expectations, tackling the migration in practice brought up more complexity than anticipated.

These are the first major hurdles we had to clear before we could even evaluate performance:

Challenge 1: Going Blind (Request Tags & Flipper)
Before we could even evaluate GraphQL performance under Cronet, our developer tooling and telemetry broke.

First, request tags were dropped. We use the cronet-okhttp bridge library so the app can keep using OkHttp's Call/Request APIs. However, OkHttp and Cronet don't share an object model. When cronet-okhttp converted an OkHttp Request into a Cronet UrlRequest, it dropped OkHttp request tags (request.tag(...)). We rely on tags to attach metadata like GraphQL operation names and logging context. Without them, downstream telemetry lost context and started receiving nulls.

To fix this, we forked cronet-okhttp and added an extension hook: a RequestToUrlRequestMapper interface that runs right before the Cronet UrlRequest.Builder is finalized:

Java
/** Hook for customizing how an OkHttp Request is mapped to a Cronet UrlRequest. */
public interface RequestToUrlRequestMapper {
  void map(Request okHttpRequest, UrlRequest.Builder urlRequestBuilder);

  RequestToUrlRequestMapper NO_OP = (okHttpRequest, urlRequestBuilder) -> {};
}

Java
// Allow client code to customize the UrlRequest before building it.
requestMapper.map(okHttpRequest, builder);
return new CronetRequestAndOkHttpResponse(
    builder.build(), createResponseSupplier(okHttpRequest, callback));
This gave us a clean seam to read tags off the OkHttp Request and attach them as Cronet request annotations.

Second, Flipper network debugging broke. Our team relies on Flipper to inspect network traffic locally, which attaches as an OkHttp network interceptor. Under cronet-okhttp, requests stopped appearing in Flipper because cronet-okhttp installs its bridge as the last application interceptor. That bridge handles execution via Cronet and returns the response directly, terminating the chain before reaching network interceptors:

None
OkHttp call
  │
  ▼
Application interceptors
  ├─ Auth / Headers / Tracing
  ├─ FlipperInterceptor (Custom) ──► Mirrors request + response to Flipper
  └─ Cronet bridge ──► Cronet ──► Response   (terminal: chain stops here)
─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ✗ Unreachable
Network interceptors  ← Flipper was originally registered here
We resolved this by writing a custom FlipperInterceptor registered as an application interceptor directly before the Cronet bridge, manually re-adding pre-bridge headers (Host, Content-Type, Content-Length) and safely peeking response bodies.

What's Next?
Restoring request tags and fixing Flipper got our local developer tooling back on track, but the GraphQL rollout was just getting started. Once we looked at production telemetry, we hit our next wave of challenges: metric numbers were wildly changed, maintaining our fork required custom bytecode tooling, and Cronet's native startup created a +2.0% cold-start TTI regression.

In Reddit's Journey to HTTP/3 on Android (Part 2), we dive into how we fixed our telemetry, eliminated the startup penalty, the results we saw in production, and where we're taking our network stack in the future.



## Reddit's Journey to HTTP/3 on Android (Part 2)
By Liam Lu, Riccardo Ciovati, and Savannah Whelan

In Part 1 of this series, we shared our vision for a unified Cronet engine, our initial rollout for media traffic, and how we forked cronet-okhttp to fix request tag tracking and Flipper debugging.

With developer tooling functional, we turned our attention to evaluating performance in production — where we immediately ran into three more major challenges: fixing distorted telemetry, maintaining our library fork safely, and eliminating a +2.0% cold-start TTI regression.

Challenge 2: The Telemetry Trap
With request tags restored and Flipper working, we looked at our dashboards — and were shocked to see massive spikes in latency and response sizes.

The culprit was where we were measuring from. Moving execution to Cronet required moving measurement from OkHttp network interceptors up to application interceptors. But those layers see fundamentally different data:

Network interceptors observe raw wire bytes before decompression, measuring true compressed size and actual network latency.

Application interceptors observe decoded, uncompressed bodies after decompression. Measuring at this layer inflated reported response sizes (unzipped bytes) and added decompression/parsing time on device to reported latency.

Furthermore, OkHttp's EventListener does not fire under Cronet because Cronet owns the underlying connections.

To restore accurate metrics, we attached Cronet's RequestFinishedInfo.Listener directly to the engine. This listener provides raw transport metrics straight from Cronet: receivedByteCount for compressed wire size, alongside exact DNS, TCP/QUIC connect, TTFB, and transfer timings. Reusing our tag remapping from Challenge 1, we pass request annotations through to Cronet, allowing the listener to associate engine-level metrics back to individual GraphQL operations.

(Note: HttpEngine exposes limited timing metrics compared to Play Services or Embedded Cronet).

We also added startup tracing around Cronet initialization, placing start/end markers on the cold-start timeline alongside process start, DI initialization, and first frame rendering.

Challenge 3: The Reality of Maintaining a Fork
Forking cronet-okhttp solved our request tag issues, but maintaining a fork in production introduced important build and infrastructure tasks:

Classpath package collisions in A/B testing: To run clean A/B experiments comparing stock cronet-okhttp against our fork, both artifacts needed to exist in the app build simultaneously. However, both declared the package com.google.net.cronet.okhttptransport, causing duplicate class build errors. We built a Gradle plugin using ASM to relocate our fork's bytecode package to com.reddit.net.cronet.okhttptransport:

Kotlin

class PackageRemapper : Remapper() {
    private val OLD = "com/google/net/cronet/okhttptransport"
    private val NEW = "com/reddit/net/cronet/okhttptransport"
    override fun map(internalName: String): String =
        if (internalName.startsWith(OLD)) internalName.replaceFirst(OLD, NEW)
        else internalName
}
Upstream contract drift: When we updated OkHttp and Okio versions, the bridge crashed on empty responses because it assumed a response body could be null, whereas newer OkHttp versions require a non-null ResponseBody. We patched the bridge to return ResponseBody.EMPTY:

Java

ResponseBody responseBody;
if (bodySource != null) {
    responseBody = createResponseBody(request, status, contentType, contentLengthString, bodySource);
} else {
    responseBody = ResponseBody.EMPTY;
}
Challenge 4: Tackling the Shipping Blocker (+2.0% TTI)
With accurate telemetry in place and our fork running cleanly, the biggest performance hurdle stood out clearly: a +2.0% cold-start TTI (Time to Interactive) regression.

Cronet is a native C++ engine that requires loading libraries, starting listener threads, initializing disk caches, and loading QUIC configs. On cold start, the app fires startup-critical queries (auth, home feed) that blocked until the network client was ready. Because this is a fixed initialization cost, faster devices saw a higher percentage hit.

To win back startup performance, we systematically tested several levers:

Eager background warmup: Instead of initializing Cronet lazily on the first request, we kick off engine initialization early during app launch on a background thread. This absorbs the initialization cost before or during request setup, recovering the full +2.0% TTI regression back to baseline.

GraphQL preconnect: Our startup tracing revealed that even after the engine was warm, the subsequent GraphQL request was still paying for DNS resolution and TLS/QUIC handshakes. As soon as Cronet is ready, we proactively open a connection to our GraphQL endpoint before startup queries fire (TTI −0.47%).

HttpEngine provider: On Android 14+ (API 34), obtaining the system-provided HttpEngine is faster than loading via Google Play Services (TTI −0.22%).

Embedded Cronet: Bundling the native engine in the APK removes Play Services IPC lookups and dynamic module loading delays (TTI −0.48%).

Stale DNS: Serving cached DNS answers immediately and refreshing asynchronously kept DNS off the hot path (TTI-neutral for startup, but maintained for media benefits).

Provider comparison summary
Provider	How it loads	APK Size Impact	Request Metrics	TTI vs. Play Services
Google Play Services Cronet	Via Play Services (CronetProviderInstaller + Dynamite module); depends on Play Services availability. IPC and module loading add init overhead.	+0 MB	Yes	Baseline (slowest)
HttpEngine	OS-provided on Android 14+ (android.net.http.HttpEngine); faster init on newer OS versions.	+0 MB	Limited	−0.22%
Embedded Cronet	Native engine bundled in APK (NativeCronetProvider); independent of Play Services with predictable startup.	~+6 MB	Yes	−0.48%
Act III: Production Rollout & Impact
With our optimizations and tooling folded in, we ran a 50/50 experiment across ~11.7 million devices per arm. TTI didn't just recover — it flipped to a win, alongside significant improvements in feed reliability and user engagement:

Metric	Change
Cold-start TTI	-0.72%
Main feed request latency	-1.36%
Feed failure rate	-10.1%
Login rate	+1.24%
A 10.1% drop in feed failures was a major stability win, proving that transport-level reliability directly moves the needle on user retention and engagement.

Act IV: Where the Road Leads Next
Shipping HTTP/3 for GraphQL wasn't the finish line — it was the milestone that finally put all our major traffic (GraphQL, images, video) on a single, shared Cronet engine. That unified foundation makes our next set of network optimizations possible:

Cross-client request prioritization: Currently, traffic types handle priorities in isolation — GraphQL uses an app-level NetworkOrchestrator queue, Glide has its own image priority model, and video requests have no per-request priority. Consequently, background prefetching can compete directly with home feed queries. Sharing a single Cronet engine allows us to map app priorities to Cronet's five native priority tiers, scheduling parallel requests, socket assignments, and stream multiplexing accordingly.

0-RTT connection resumption for read queries: QUIC 0-RTT allows returning clients to send data in the initial packet, removing a round trip from connection setup. Because 0-RTT payloads are vulnerable to replay attacks, CDNs restrict 0-RTT to idempotent HTTP GET requests. Since most of our GraphQL traffic currently uses POST, we are transitioning key read queries to GET before enabling 0-RTT.

Wrapping Up
Swapping the transport was the easy part — most of the work went into startup performance, developer tooling, and telemetry. Working through those requirements at scale directly improved app reliability for millions of Redditors, leaving us with a foundation we're excited to keep building on.

Special thanks to the folks from Android Platform, Data Science, Media Foundation, Feed Experience, Growth, GraphQL, and Security teams for reviewing PRs, specs, and readouts, chasing down startup regressions, and supporting us along the way — it truly took a village.