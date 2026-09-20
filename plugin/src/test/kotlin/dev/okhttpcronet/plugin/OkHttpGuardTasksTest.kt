package dev.okhttpcronet.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Exercises the fingerprint task's per-variant extraction (android AAR classes.jar nesting,
 * jvm flat jar) against synthetic artifacts built from the committed goldens, plus the
 * mismatch-vs-warning decision.
 */
class OkHttpGuardTasksTest {

    private val recipe = RecipeRegistry.forVersion("5.5.0")

    @Test
    fun `android AAR extraction yields the golden class hash`() {
        val aar = zipOf(
            mapOf(
                "AndroidManifest.xml" to "<manifest />".toByteArray(),
                "classes.jar" to jarOf(stock("5.5.0", Variant.ANDROID)),
            ),
        )
        assertEquals(
            recipe.fingerprints.getValue(Variant.ANDROID),
            connectInterceptorSha256(aar, recipe.fingerprintArtifacts.getValue(Variant.ANDROID), Variant.ANDROID),
        )
    }

    @Test
    fun `jvm flat jar extraction yields the golden class hash`() {
        val jar = zipOf(mapOf(CONNECT_INTERCEPTOR_TEST_ENTRY to stock("5.5.0", Variant.JVM)))
        assertEquals(
            recipe.fingerprints.getValue(Variant.JVM),
            connectInterceptorSha256(jar, recipe.fingerprintArtifacts.getValue(Variant.JVM), Variant.JVM),
        )
    }

    @Test
    fun `mutated class inside the jvm jar mismatches the recipe fingerprint`() {
        val mutated = tamper(stock("5.5.0", Variant.JVM)) { insn ->
            object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val renamed = if (name == "proceed") "proceedTampered" else name
                    super.visitMethodInsn(opcode, owner, renamed, descriptor, isInterface)
                }
            }
        }
        val jar = zipOf(mapOf(CONNECT_INTERCEPTOR_TEST_ENTRY to mutated))
        val actual = connectInterceptorSha256(jar, recipe.fingerprintArtifacts.getValue(Variant.JVM), Variant.JVM)
        assertFalse(recipe.fingerprints.getValue(Variant.JVM).equals(actual, ignoreCase = true))
    }

    @Test
    fun `missing entry fails closed`() {
        val jar = zipOf(mapOf("okhttp3/internal/Other.class" to stock("5.5.0", Variant.JVM)))
        assertThrows(org.gradle.api.GradleException::class.java) {
            connectInterceptorBytes(jar, recipe.fingerprintArtifacts.getValue(Variant.JVM), Variant.JVM)
        }
    }

    @Test
    fun `matching hashes yield no failure message`() {
        assertNull(fingerprintFailure("g:a:1", "abc", "ABC", allowUnfingerprinted = false))
    }

    @Test
    fun `mismatch without the escape hatch yields the plain failure message`() {
        val message = fingerprintFailure("g:a:1", "abc", "def", allowUnfingerprinted = false)!!
        assertTrue(message.contains("fingerprint mismatch for g:a:1"))
        assertTrue(message.contains("expected=abc actual=def"))
        assertFalse(message.contains("allowUnfingerprinted"))
    }

    @Test
    fun `mismatch with the escape hatch warns but keeps the structural guard hard`() {
        val message = fingerprintFailure("g:a:1", "abc", "def", allowUnfingerprinted = true)!!
        assertTrue(message.contains("allowUnfingerprinted=true tolerates the fingerprint mismatch"))
        assertTrue(message.contains("structural bytecode guard still hard-fails any shape drift"))
    }

    @Test
    fun `pinDecision accepts every supported version`() {
        for (v in RecipeRegistry.recipes.keys) {
            assertEquals(v, PinDecision.Ok, pinDecision(setOf(v)))
        }
    }

    @Test
    fun `pinDecision warns on newer untested versions`() {
        val decision = pinDecision(setOf("5.5.1"))
        assertTrue(decision is PinDecision.Warn)
        assertTrue((decision as PinDecision.Warn).message.contains("UNTESTED"))
    }

    @Test
    fun `failOnUntested promotes an untested warning to a failure`() {
        val warned = pinDecision(setOf("5.5.1"))
        val failed = applyFailOnUntested(warned, failOnUntested = true)
        assertTrue(failed is PinDecision.Fail)
        assertTrue((failed as PinDecision.Fail).message.contains("UNTESTED"))
        assertEquals(warned, applyFailOnUntested(warned, failOnUntested = false))
        assertEquals(PinDecision.Ok, applyFailOnUntested(PinDecision.Ok, failOnUntested = true))
    }

    @Test
    fun `pinDecision fails on older and okhttp 4`() {
        for (v in listOf("4.12.0", "5.3.2")) {
            val decision = pinDecision(setOf(v))
            assertTrue(v, decision is PinDecision.Fail)
            assertTrue(v, (decision as PinDecision.Fail).message.contains("not supported"))
        }
    }

    @Test
    fun `pinDecision fails when okhttp is missing or mixed`() {
        assertTrue(pinDecision(emptySet()) is PinDecision.Fail)
        assertTrue(pinDecision(setOf("5.4.0", "5.5.0")) is PinDecision.Fail)
    }
}

private const val CONNECT_INTERCEPTOR_TEST_ENTRY = "okhttp3/internal/connection/ConnectInterceptor.class"

private fun zipOf(entries: Map<String, ByteArray>): File {
    val file = File.createTempFile("okhttp-cronet-guard-test", ".zip")
    file.deleteOnExit()
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return file
}

/** Flat jar (okhttp-jvm layout) holding exactly one class entry. */
private fun jarOf(classBytes: ByteArray): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        zip.putNextEntry(ZipEntry(CONNECT_INTERCEPTOR_TEST_ENTRY))
        zip.write(classBytes)
        zip.closeEntry()
    }
    return buffer.toByteArray()
}
