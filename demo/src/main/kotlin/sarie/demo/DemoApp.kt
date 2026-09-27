package sarie.demo

import android.app.Application
import androidx.annotation.OptIn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.chromium.net.QuicOptions
import sarie.bridge.SarieBridge

class DemoApp : Application() {
    lateinit var clients: Clients
        private set

    @OptIn(markerClass = [QuicOptions.Experimental::class])
    override fun onCreate() {
        super.onCreate()
        instance = this

        Thread {
            // Hints are read at engine build: hosts added in the benchmark's URL editor get one on the
            // next launch (until then Cronet discovers h3 through Alt-Svc).
            val benchHosts = BenchUrls.load(this).mapNotNull { it.toHttpUrlOrNull()?.host }
            SarieBridge.install(this) {
                debugLogger(DemoLog)
                listener(DemoLog)
                configure { builder ->
                    for (host in (DEMO_HOSTS + benchHosts).distinct()) {
                        builder.addQuicHint(host, 443, 443)
                    }
                    // Short idle timeout so the connection benchmark can reach a resumed (0-RTT)
                    // QUIC connection in a few seconds instead of Cronet's default 30 s.
                    builder.setQuicOptions(
                        QuicOptions.builder()
                            .setIdleConnectionTimeoutSeconds(ConnectionBench.QUIC_IDLE_SECONDS.toLong())
                            .build(),
                    )
                }
            }
        }.start()

        clients = createClients(this)
    }

    companion object {
        lateinit var instance: DemoApp
            private set

        val DEMO_HOSTS = listOf(
            "images.unsplash.com",
            "cloudflare-quic.com",
            "www.google.com",
            "cdn.jsdelivr.net",
        )
    }
}
