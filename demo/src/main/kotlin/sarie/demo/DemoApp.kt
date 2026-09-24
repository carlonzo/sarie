package sarie.demo

import android.app.Application
import sarie.bridge.SarieBridge

class DemoApp : Application() {
    lateinit var clients: Clients
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        SarieBridge.install(this) {
            configure { builder ->
                for (host in DEMO_HOSTS) {
                    builder.addQuicHint(host, 443, 443)
                }
            }
        }

        clients = createClients()
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
