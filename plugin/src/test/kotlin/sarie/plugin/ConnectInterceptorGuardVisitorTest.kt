package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ConnectInterceptorGuardVisitorTest {

    @Test
    fun `target name matches exactly the registered classes`() {
        assertTrue(isTargetClass("okhttp3.internal.connection.ConnectInterceptor"))
        assertTrue(isTargetClass("okhttp3.internal.http.CallServerInterceptor"))
        assertTrue(isTargetClass("okhttp3.Cache\$Entry"))
        assertTrue(isTargetClass("okhttp3.internal.cache.CacheStrategy\$Factory"))
        assertTrue(isTargetClass("okhttp3.internal.connection.RealCall"))
        for (name in listOf(
            "okhttp3.internal.connection.RealCall\$AsyncCall",
            "okhttp3.internal.connection.ConnectInterceptorKt",
            "okhttp3.internal.connection.ConnectInterceptor\$Chain",
            "okhttp3.internal.connection.ConnectInterceptorFactory",
            "okhttp3.internal.http.CallServerInterceptorKt",
            "okhttp3.Interceptor",
            "okhttp3.Cache",
            "okhttp3.internal.cache.CacheStrategy",
            "okhttp3.internal.cache.CacheInterceptor",
            "com.example.Foo",
            "",
        )) {
            assertFalse(name, isTargetClass(name))
        }
    }

    @Test
    fun `guard passes stock golden and emits the trampoline for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val out = guarded(version, stock(version, variant))
            assertEquals("$version/$variant", expectedTrampoline, interceptInstructions(out))
        }
    }

    @Test
    fun `guard keeps every other member identical to stock for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = stock(version, variant)
            val out = guarded(version, golden)
            assertEquals("$version/$variant", memberDump(golden), memberDump(out))
        }
    }

    @Test
    fun `guard fails closed on tampered proceed call`() {
        val tampered = tamper(stock("5.5.0", Variant.ANDROID)) { insn ->
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
        val e = assertThrows(IllegalStateException::class.java) { guarded("5.5.0", tampered) }
        // The failure carries a javap-style dump of the captured instructions.
        assertTrue(e.message, e.message!!.contains("proceedTampered"))
        assertTrue(e.message, e.message!!.contains("CHECKCAST okhttp3/internal/http/RealInterceptorChain"))
    }

    @Test
    fun `guard fails closed on missing checkcast`() {
        val tampered = tamper(stock("5.5.0", Variant.JVM)) { insn ->
            object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (opcode != Opcodes.CHECKCAST) super.visitTypeInsn(opcode, type)
                }
            }
        }
        val e = assertThrows(IllegalStateException::class.java) { guarded("5.5.0", tampered) }
        assertTrue(e.message, e.message!!.contains("does not match the pinned stock shape"))
    }

    @Test
    fun `guard fails closed when intercept is missing from the target class`() {
        val noIntercept = minimalClassBytes()
        assertThrows(IllegalStateException::class.java) { guarded("5.5.0", noIntercept) }
    }

    @Test
    fun `guard splices the notifyCanceled hook in front of the RealCall listener call`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = realCallStock(version, variant)
            val out = guarded(version, golden)
            val insns = cancelInsns(out)
            assertEquals("$version/$variant", 1, insns.count { it.startsWith(NOTIFY_CANCELED) })

            val stock = RealCallCancelGuard.EXPECTED
            val listenerAt = stock.indexOfFirst { it.startsWith(LISTENER_LOAD) }
            assertTrue("$version/$variant has no listener load to anchor on", listenerAt >= 0)
            assertEquals("$version/$variant", stock.size + 2, insns.size)
            assertEquals("$version/$variant", stock.subList(0, listenerAt), insns.subList(0, listenerAt))
            assertEquals("$version/$variant", "ALOAD 0", insns[listenerAt])
            assertTrue("$version/$variant", insns[listenerAt + 1].startsWith(NOTIFY_CANCELED))
            assertEquals(
                "$version/$variant",
                stock.subList(listenerAt, stock.size),
                insns.subList(listenerAt + 2, insns.size),
            )
            assertEquals("$version/$variant", memberDump(golden, "cancel()V"), memberDump(out, "cancel()V"))
        }
    }

    @Test
    fun `guard fails closed on a tampered RealCall cancel`() {
        val tampered = tamperMethod(
            realCallStock("5.5.0", Variant.ANDROID),
            InstrumentationTargets.CANCEL_NAME,
            InstrumentationTargets.CANCEL_DESC,
        ) { insn ->
            object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitFieldInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                ) {
                    // Drop the `canceled` flag write, so the fast path no longer guards anything.
                    if (opcode != Opcodes.PUTFIELD) {
                        super.visitFieldInsn(opcode, owner, name, descriptor)
                    }
                }
            }
        }
        val e = assertThrows(IllegalStateException::class.java) { guarded("5.5.0", tampered) }
        assertTrue(
            e.message,
            e.message!!.contains("cancel()V in okhttp3/internal/connection/RealCall"),
        )
        assertTrue(
            e.message,
            e.message!!.contains(
                "instruction 6: expected PUTFIELD okhttp3/internal/connection/RealCall.canceled Z, " +
                    "found ALOAD 0",
            ),
        )
    }

    @Test
    fun `guard fails closed when cancel is missing from RealCall`() {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            InstrumentTarget.REAL_CALL.internalName,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        val e = assertThrows(IllegalStateException::class.java) {
            guarded("5.5.0", writer.toByteArray())
        }
        assertTrue(e.message, e.message!!.contains("cancel()V not found"))
    }

    /** Runs the stock class through the guarding visitor into a COMPUTE_FRAMES writer. */
    private fun guarded(version: String, classBytes: ByteArray): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val guard = RecipeRegistry.forVersion(version).guard
        ClassReader(classBytes).accept(ConnectInterceptorGuardVisitor(writer, guard), ClassReader.SKIP_FRAMES)
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
