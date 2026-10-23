package sarie.bridge.mapping

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadBodyDataBrokerTest {

    @Test
    fun `write restores buffer limit before completing future on success`() {
        val broker = UploadDataProviders.UploadBodyDataBroker(writeTimeoutMillis = 10_000L)
        val readBuffer = ByteBuffer.allocate(64)
        val future = broker.enqueueBodyRead(readBuffer) as CompletableFuture<UploadDataProviders.ReadResult>
        val observedLimit = AtomicInteger(-1)
        future.whenComplete { _, _ ->
            observedLimit.set(readBuffer.limit())
            readBuffer.limit(7)
        }

        broker.write(Buffer().writeUtf8("abc"), 3L)

        assertEquals(64, observedLimit.get())
        assertEquals(7, readBuffer.limit())
    }

    @Test
    fun `write restores buffer limit before completing future on error`() {
        val broker = UploadDataProviders.UploadBodyDataBroker(writeTimeoutMillis = 10_000L)
        val readBuffer = ByteBuffer.allocate(64)
        val future = broker.enqueueBodyRead(readBuffer) as CompletableFuture<UploadDataProviders.ReadResult>
        val observedLimit = AtomicInteger(-1)
        future.whenComplete { _, _ ->
            observedLimit.set(readBuffer.limit())
            readBuffer.limit(7)
        }

        val emptySource = Buffer()
        assertThrows(IOException::class.java) {
            broker.write(emptySource, 3L)
        }

        assertEquals(64, observedLimit.get())
        assertEquals(7, readBuffer.limit())
    }
}
