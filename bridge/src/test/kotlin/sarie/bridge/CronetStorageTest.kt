package sarie.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronetStorageTest {
    @Test
    fun `main process returns default storage dir`() {
        assertEquals("cronet-cache", cronetStorageDirName("com.example.app", "com.example.app"))
        assertEquals("cronet-cache", cronetStorageDirName("com.sample", "com.sample"))
    }

    @Test
    fun `process with colon suffix returns suffix in dir name`() {
        assertEquals("cronet-cache-remote", cronetStorageDirName("com.example.app:remote", "com.example.app"))
        assertEquals("cronet-cache-remote", cronetStorageDirName(":remote", "com.example.app"))
        assertEquals("cronet-cache-remote", cronetStorageDirName(":remote"))
        assertEquals("cronet-cache-push", cronetStorageDirName("com.example.app:push", "com.example.app"))
    }

    @Test
    fun `process with odd characters sanitizes suffix`() {
        assertEquals(
            "cronet-cache-sub_proc_123",
            cronetStorageDirName("com.example.app:sub/proc@123", "com.example.app"),
        )
        assertEquals(
            "cronet-cache-foo_bar_baz_test",
            cronetStorageDirName("com.example.app:foo\$bar#baz!test", "com.example.app"),
        )
        assertEquals(
            "cronet-cache-com.example.isolated_service",
            cronetStorageDirName("com.example.isolated_service", "com.example.app"),
        )
        // Allowed characters [A-Za-z0-9._-] are preserved
        assertEquals(
            "cronet-cache-a-B_1.2",
            cronetStorageDirName("com.example.app:a-B_1.2", "com.example.app"),
        )
        // Empty suffix after colon falls back to default
        assertEquals("cronet-cache", cronetStorageDirName("com.example.app:", "com.example.app"))
    }

    @Test
    fun `unreadable process name returns default storage dir`() {
        assertEquals("cronet-cache", cronetStorageDirName(null, "com.example.app"))
        assertEquals("cronet-cache", cronetStorageDirName("", "com.example.app"))
        assertEquals("cronet-cache", cronetStorageDirName("   ", "com.example.app"))
        assertEquals("cronet-cache", cronetStorageDirName(null, null))
    }

    @Test
    fun `readProcessNameFromCmdline extracts name up to first NUL byte`() {
        val temp = File.createTempFile("cmdline_test", ".tmp")
        try {
            temp.writeBytes("com.example.app\u0000--arg1\u0000--arg2\u0000".toByteArray(Charsets.US_ASCII))
            assertEquals("com.example.app", readProcessNameFromCmdline(temp))
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `readProcessNameFromCmdline returns null for empty or non-existent file`() {
        val temp = File.createTempFile("cmdline_empty", ".tmp")
        try {
            assertNull(readProcessNameFromCmdline(temp))
        } finally {
            temp.delete()
        }

        val nonExistent = File("/path/to/definitely/nonexistent/proc/cmdline")
        assertNull(readProcessNameFromCmdline(nonExistent))
    }
}
