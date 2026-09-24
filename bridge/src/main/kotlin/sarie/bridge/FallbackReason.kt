package sarie.bridge

/**
 * Why a call went to stock OkHttp instead of Cronet. Delivered to [SarieListener.onRouted]
 * on the caller thread before any network I/O, or printed as `reason=<value>` by
 * [SarieLogger.Logcat]. When a request is served by Cronet, the reason is null.
 *
 * Reasons fall into three categories:
 * - Fixable via [SarieConfig.Builder] or request adjustments ([pins], [allowlist],
 *   [content_type], [engine_missing], [content_encoding], and loopback [cleartext]).
 * - Intentional controls ([disabled], [tag_opt_out]).
 * - Unsupported by Cronet, staying on OkHttp by design ([cleartext], [websocket],
 *   [h2_prior_knowledge], [proxy], [socket_factory], [hostname_verifier], [trust], [policy_error]).
 */
public enum class FallbackReason {
    /**
     * The bridge is disabled at runtime via system property `okhttp.cronet.enabled=false`
     * or [CronetPolicy.enabled] returned `false`.
     *
     * Why Cronet cannot serve: Intentional emergency kill switch or host policy gate.
     *
     * Resolution: Intentional. Re-enable the system property or return `true` from policy.
     */
    disabled,

    /**
     * No active [org.chromium.net.CronetEngine] snapshot is available. This occurs if
     * [SarieBridge.install] has not been called, has not finished initializing in the
     * background (e.g. while Google Play Services downloads Cronet), or found no eligible
     * provider in the APK or platform.
     *
     * Why Cronet cannot serve: Requests cannot be processed without an active engine.
     *
     * Resolution: Add an enabled Cronet provider artifact (`cronet-embedded` or
     * `play-services-cronet`), and invoke [SarieBridge.install] early during app startup
     * off the main thread before dispatching requests.
     */
    engine_missing,

    /**
     * The request is tagged with [CronetOptOut] (`request.tag(CronetOptOut::class.java, CronetOptOut)`).
     *
     * Why Cronet cannot serve: The caller explicitly requested stock OkHttp execution for this call.
     *
     * Resolution: Intentional per-request opt-out. Remove the tag to permit Cronet routing.
     */
    tag_opt_out,

    /**
     * The request origin (`host` or `host:port`) is not present in [CronetPolicy.allowedOrigins].
     *
     * Why Cronet cannot serve: Host policy restricts Cronet traffic to explicit allowlisted origins.
     *
     * Resolution: Add the origin to [DefaultPolicy.Builder.allowedOrigins], or leave the set empty
     * (the default) or use `"*"` to allow all HTTPS origins.
     */
    allowlist,

    /**
     * The request URL uses plain `http://`, or uses `https://` targeting a loopback address
     * (`127.0.0.1`, `localhost`, `::1`) without loopback HTTPS enabled.
     *
     * Why Cronet cannot serve: Plain HTTP is not supported over Cronet by design (the Cronet path
     * requires HTTPS). Loopback HTTPS is blocked by default to prevent unintentional local proxy
     * or test traffic routing over Cronet.
     *
     * Resolution: Cleartext `http://` stays on OkHttp by design. For local test servers using
     * loopback HTTPS, configure [DefaultPolicy.Builder.allowLoopbackHttps] with `true`.
     */
    cleartext,

    /**
     * The call was opened with `OkHttpClient.newWebSocket`.
     *
     * Why Cronet cannot serve: Cronet's bidirectional stream API does not integrate with
     * OkHttp's WebSocket lifecycle or listener protocol.
     *
     * Resolution: Stays on OkHttp by design.
     */
    websocket,

    /**
     * The client is configured with `okhttp3.Protocol.H2_PRIOR_KNOWLEDGE`.
     *
     * Why Cronet cannot serve: Cronet negotiates protocols dynamically via ALPN and QUIC,
     * and does not support forced cleartext HTTP/2 prior knowledge.
     *
     * Resolution: Stays on OkHttp by design.
     */
    h2_prior_knowledge,

    /**
     * The client has an explicit [java.net.Proxy] configured or its [java.net.ProxySelector]
     * selects a non-`DIRECT` proxy.
     *
     * Why Cronet cannot serve: Cronet manages its own proxy configuration at the engine level
     * and cannot inherit per-client OkHttp proxy settings.
     *
     * Resolution: Stays on OkHttp by design.
     */
    proxy,

    /**
     * The client is configured with a custom [javax.net.SocketFactory] (other than the
     * platform default [javax.net.SocketFactory.getDefault]).
     *
     * Why Cronet cannot serve: Cronet manages low-level sockets natively in Chromium and
     * cannot route traffic through Java socket factories.
     *
     * Resolution: Stays on OkHttp by design.
     */
    socket_factory,

    /**
     * The client is configured with a custom [javax.net.ssl.HostnameVerifier] (other than
     * OkHttp's default `OkHostnameVerifier`).
     *
     * Why Cronet cannot serve: Cronet validates TLS certificates and server hostnames
     * internally using Chromium's verification stack.
     *
     * Resolution: Stays on OkHttp by design.
     */
    hostname_verifier,

    /**
     * The request host matches an OkHttp `CertificatePinner` rule, but either the engine
     * was borrowed (which installs no pins), the pins differ from what was passed to
     * [SarieConfig.Builder.certificatePinner], or a single-label wildcard pattern (`*.host`) was used.
     *
     * Why Cronet cannot serve: Cronet pins are fixed at engine construction time and do not
     * support single-label wildcards (`*.host`).
     *
     * Resolution: Provide the client's `CertificatePinner` to [SarieConfig.Builder.certificatePinner]
     * during a built [SarieBridge.install], and use exact hostnames or double-wildcards (`**.host`).
     */
    pins,

    /**
     * The client uses a custom [javax.net.ssl.X509TrustManager] or custom CA certificates whose
     * class or accepted issuer certificates differ from the platform default trust manager.
     *
     * Why Cronet cannot serve: Cronet validates TLS certificate chains against platform
     * system trust anchors and cannot use OkHttp's custom Java `KeyStore` or trust managers.
     *
     * Resolution: Stays on OkHttp by design.
     */
    trust,

    /**
     * The request sets a manual `Accept-Encoding` header, or overrides compression headers
     * without including `gzip`.
     *
     * Why Cronet cannot serve: Cronet manages compression and transparent decompression
     * (`gzip, deflate`) internally. When an app requests manual or unhandled encodings, the
     * bridge steps aside so OkHttp and the app can coordinate decompression.
     *
     * Resolution: Remove manual `Accept-Encoding` headers from the request (Cronet decompresses
     * automatically), or ensure `gzip` is listed in the header value.
     */
    content_encoding,

    /**
     * The host's custom [CronetPolicy.enabled] implementation threw an uncaught exception.
     *
     * Why Cronet cannot serve: The bridge fails closed to stock OkHttp for safety.
     *
     * Resolution: Stays on OkHttp by design: fix the exception thrown inside the host policy.
     */
    policy_error,

    /**
     * The request body has nonzero or unknown (-1) length and no `Content-Type` was specified
     * on either the `RequestBody` or the request headers.
     *
     * Why Cronet cannot serve: Cronet automatically injects `Content-Type: application/octet-stream`
     * when none is provided, which can violate API contracts expecting no content type or a specific format.
     *
     * Resolution: Provide an explicit `Content-Type` on the `RequestBody` (e.g. via `toRequestBody("...".toMediaType())`)
     * or add a `Content-Type` header to the request.
     */
    content_type,
}
