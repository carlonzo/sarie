package dev.okhttpcronet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class ConnectInterceptorGuardVisitorTest {

    private val variants = listOf("android", "jvm")

    @Test
    fun `target name matches only the exact ConnectInterceptor class`() {
        assertTrue(isTargetClass("okhttp3.internal.connection.ConnectInterceptor"))
        for (name in listOf(
            "okhttp3.internal.connection.ConnectInterceptorKt",
            "okhttp3.internal.connection.ConnectInterceptor\$Chain",
            "okhttp3.internal.connection.ConnectInterceptorFactory",
            "okhttp3.Interceptor",
            "com.example.Foo",
            "",
        )) {
            assertFalse(name, isTargetClass(name))
        }
    }

    @Test
    fun `guard passes stock golden and emits the trampoline for both variants`() {
        for (variant in variants) {
            val out = guarded(stock(variant))
            assertEquals(variant, expectedTrampoline, interceptInstructions(out))
        }
    }

    @Test
    fun `guard keeps every other member identical to stock for both variants`() {
        for (variant in variants) {
            val out = guarded(stock(variant))
            assertEquals(variant, memberDump(stock(variant)), memberDump(out))
        }
    }

    @Test
    fun `guard fails closed on tampered proceed call`() {
        val tampered = tamper(stock("android")) { insn ->
            object : MethodVisitor(Opcodes.ASM9, insn) {
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
        val e = assertThrows(IllegalStateException::class.java) { guarded(tampered) }
        assertTrue(e.message, e.message!!.contains("okhttp-cronet"))
        // The failure carries a javap-style dump of the captured instructions.
        assertTrue(e.message, e.message!!.contains("proceedTampered"))
        assertTrue(e.message, e.message!!.contains("CHECKCAST okhttp3/internal/http/RealInterceptorChain"))
    }

    @Test
    fun `guard fails closed on missing checkcast`() {
        val tampered = tamper(stock("jvm")) { insn ->
            object : MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (opcode != Opcodes.CHECKCAST) super.visitTypeInsn(opcode, type)
                }
            }
        }
        val e = assertThrows(IllegalStateException::class.java) { guarded(tampered) }
        assertTrue(e.message, e.message!!.contains("okhttp-cronet"))
    }

    @Test
    fun `guard fails closed when intercept is missing from the target class`() {
        val noIntercept = minimalClassBytes()
        assertThrows(IllegalStateException::class.java) { guarded(noIntercept) }
    }

    /** Runs the stock class through the guarding visitor into a COMPUTE_FRAMES writer. */
    private fun guarded(classBytes: ByteArray): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        ClassReader(classBytes).accept(ConnectInterceptorGuardVisitor(writer), ClassReader.SKIP_FRAMES)
        return writer.toByteArray()
    }

    /** Rewrites the intercept method with [mutate] wrapping the passthrough visitor. */
    private fun tamper(classBytes: ByteArray, mutate: (MethodVisitor) -> MethodVisitor): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val passthrough = super.visitMethod(access, name, descriptor, signature, exceptions)
                return if (name == "intercept" && descriptor == TRAMPOLINE_DESC) mutate(passthrough) else passthrough
            }
        }, 0)
        return writer.toByteArray()
    }

    private fun minimalClassBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "okhttp3/internal/connection/ConnectInterceptor",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }
}
