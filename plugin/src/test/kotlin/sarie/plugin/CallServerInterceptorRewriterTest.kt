package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * CallServerInterceptor prefix injection over every pinned recipe. The stock body stays;
 * other methods are untouched; a non-target class is not rewritten.
 */
class CallServerInterceptorRewriterTest {

    @Test
    fun `prefix is injected after the Kotlin preamble for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val rewritten = CallServerRewriter.rewrite(callServerStock(version, variant))
            val insns = recordedInsns(rewritten)
            assertEquals("$version/$variant", PREAMBLE_AND_PREFIX, insns.take(PREAMBLE_AND_PREFIX.size))
            assertTrue("$version/$variant", insns[6].startsWith("IFNULL "))
            assertEquals("$version/$variant", "ARETURN", insns[7])
            assertEquals("$version/$variant", "POP", insns[8])
            assertEquals("$version/$variant", "ALOAD 1", insns[9])
            assertEquals(
                "$version/$variant",
                "CHECKCAST okhttp3/internal/http/RealInterceptorChain",
                insns[10],
            )
            assertTrue(
                "$version/$variant",
                insns.any {
                    it.startsWith("INVOKEVIRTUAL okhttp3/internal/connection/Exchange.writeRequestHeaders ")
                },
            )
        }
    }

    @Test
    fun `other methods stay identical to stock for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = callServerStock(version, variant)
            val rewritten = CallServerRewriter.rewrite(golden)
            assertEquals("$version/$variant", memberDump(golden), memberDump(rewritten))
        }
    }

    @Test
    fun `rewritten output re-parses cleanly for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val rewritten = CallServerRewriter.rewrite(callServerStock(version, variant))
            ClassReader(rewritten).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {}, ClassReader.EXPAND_FRAMES)
            assertEquals(
                InstrumentTarget.CALL_SERVER_INTERCEPTOR.internalName,
                ClassReader(rewritten).className,
            )
        }
    }

    @Test
    fun `a non-target class is not rewritten`() {
        val other = minimalClass("com/example/NotCallServer")
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            CallServerRewriter.rewrite(other)
        }
        assertTrue(error.message, error.message!!.contains("okhttp-cronet"))
    }

    @Test
    fun `guard accepts stock and the visitor emits the prefix for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = callServerStock(version, variant)
            assertEquals("$version/$variant", emptyList<String>(), CallServerGuard.verify(recordedInsns(golden)))
            val out = guarded(version, golden)
            val insns = recordedInsns(out)
            assertEquals("$version/$variant", PREAMBLE_AND_PREFIX, insns.take(PREAMBLE_AND_PREFIX.size))
            assertEquals("$version/$variant", memberDump(golden), memberDump(out))
        }
    }

    @Test
    fun `guard fails closed when the stock body shape drifts`() {
        val tampered = tamper(callServerStock("5.5.0", Variant.JVM)) { insn ->
            object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val renamed = if (name == "writeRequestHeaders") "writeRequestHeadersTampered" else name
                    super.visitMethodInsn(opcode, owner, renamed, descriptor, isInterface)
                }
            }
        }
        val problems = CallServerGuard.verify(recordedInsns(tampered))
        assertTrue(problems.toString(), problems.any { it.contains("stock body shape mismatch") })
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            guarded("5.5.0", tampered)
        }
        assertTrue(error.message, error.message!!.contains("okhttp-cronet"))
        assertTrue(error.message, error.message!!.contains("writeRequestHeadersTampered"))
    }

    @Test
    fun `guard fails closed when the F7 prefix drifts`() {
        val tampered = tamper(callServerStock("5.4.0", Variant.ANDROID)) { insn ->
            object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitLdcInsn(value: Any) {
                    if (value == "chain") super.visitLdcInsn("chainTampered") else super.visitLdcInsn(value)
                }
            }
        }
        val problems = CallServerGuard.verify(recordedInsns(tampered))
        assertTrue(problems.toString(), problems.any { it.contains("pinned F7 prefix") })
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { guarded("5.4.0", tampered) }
    }

    private fun guarded(version: String, classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer = CallServerRewriter.framingWriter(reader)
        val guard = RecipeRegistry.forVersion(version).guard
        reader.accept(ConnectInterceptorGuardVisitor(writer, guard), ClassReader.SKIP_FRAMES)
        return writer.toByteArray()
    }

    private fun minimalClass(internalName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}

private val PREAMBLE_AND_PREFIX = listOf(
    "ALOAD 1",
    "LDC chain",
    "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNullParameter (Ljava/lang/Object;Ljava/lang/String;)V",
    "ALOAD 1",
    "INVOKESTATIC sarie/bridge/CronetBridge.callServer $TRAMPOLINE_DESC",
    "DUP",
)
