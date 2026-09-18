package dev.okhttpcronet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class FingerprintTest {

    @Test
    fun `baked constants equal sha256 of the stock goldens`() {
        assertEquals(Fingerprint.OKHTTP_ANDROID_CLASS_SHA256, Fingerprint.sha256Hex(stock("android")))
        assertEquals(Fingerprint.OKHTTP_JVM_CLASS_SHA256, Fingerprint.sha256Hex(stock("jvm")))
    }

    @Test
    fun `mutated intercept body is detected with expected and actual hashes in the message`() {
        val mutated = mutateInterceptBody(stock("jvm"))
        val mutatedHash = Fingerprint.sha256Hex(mutated)
        assertNotEquals(Fingerprint.OKHTTP_JVM_CLASS_SHA256, mutatedHash)

        // The fingerprint check (as todo 7's guard will use it) must fail with BOTH hashes visible.
        val error = assertThrows(AssertionError::class.java) {
            assertEquals(Fingerprint.OKHTTP_JVM_CLASS_SHA256, Fingerprint.sha256Hex(mutated))
        }
        assertTrue(error.message!!.contains(Fingerprint.OKHTTP_JVM_CLASS_SHA256))
        assertTrue(error.message!!.contains(mutatedHash))
    }
}

/**
 * Flip one instruction inside the intercept method body: the first invoke's interface flag
 * is inverted. Everything else is copied verbatim (ClassWriter(0) keeps original frames).
 */
private fun mutateInterceptBody(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val target = super.visitMethod(access, name, descriptor, signature, exceptions)
            if (name != "intercept" || descriptor != TRAMPOLINE_DESC) return target
            var mutated = false
            return object : MethodVisitor(Opcodes.ASM9, target) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    if (!mutated) {
                        mutated = true
                        super.visitMethodInsn(opcode, owner, name, descriptor, !isInterface)
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }, 0)
    return writer.toByteArray()
}
