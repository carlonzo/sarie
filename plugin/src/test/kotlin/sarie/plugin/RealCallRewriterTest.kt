package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The fifth rewrite site, over the stock bytecode resolved from the OkHttp jar: `cancel()` keeps its body
 * and gains exactly one `INVOKESTATIC CronetBridge.notifyCanceled` in front of the terminal
 * `RETURN`. Anything but the pinned stream fails closed.
 */
class RealCallRewriterTest {

    @Test
    fun `cancel gains exactly one notifyCanceled hook in front of the listener call`() {
        for ((version, variant) in allRecipeVariants()) {
            val insns = cancelInsns(RealCallRewriter.rewrite(realCallStock(version, variant)))
            assertEquals("$version/$variant", 1, insns.count { it.startsWith(NOTIFY_CANCELED) })

            val stock = RealCallCancelGuard.EXPECTED
            val listenerAt = stock.indexOfFirst { it.startsWith(LISTENER_LOAD) }
            assertTrue("$version/$variant has no listener load to anchor on", listenerAt >= 0)
            assertEquals("$version/$variant", stock.size + 2, insns.size)

            // Stock is untouched up to the listener call...
            assertEquals("$version/$variant", stock.subList(0, listenerAt), insns.subList(0, listenerAt))
            // ...the hook goes directly in front of it, so a throwing host listener cannot skip it...
            assertEquals("$version/$variant", "ALOAD 0", insns[listenerAt])
            assertTrue("$version/$variant", insns[listenerAt + 1].startsWith(NOTIFY_CANCELED))
            // ...and the rest of the stock teardown is replayed verbatim.
            assertEquals(
                "$version/$variant",
                stock.subList(listenerAt, stock.size),
                insns.subList(listenerAt + 2, insns.size),
            )
            assertEquals("$version/$variant", "RETURN", insns.last())
        }
    }

    @Test
    fun `the already-canceled fast path returns before the hook`() {
        for ((version, variant) in allRecipeVariants()) {
            val insns = cancelInsns(RealCallRewriter.rewrite(realCallStock(version, variant)))
            val hookAt = insns.indexOfFirst { it.startsWith(NOTIFY_CANCELED) }
            assertTrue("$version/$variant", hookAt > 3)
            // ALOAD 0 / GETFIELD canceled / IFEQ L0 / RETURN is stock: no hook on that path.
            assertEquals(
                "$version/$variant",
                listOf(
                    "ALOAD 0",
                    "GETFIELD okhttp3/internal/connection/RealCall.canceled Z",
                    "IFEQ L0",
                    "RETURN",
                ),
                insns.subList(0, 4),
            )
        }
    }

    @Test
    fun `every other RealCall member is copied through unchanged`() {
        for ((version, variant) in allRecipeVariants()) {
            val stock = realCallStock(version, variant)
            val rewritten = RealCallRewriter.rewrite(stock)
            assertEquals(InstrumentTarget.REAL_CALL.internalName, ClassReader(rewritten).className)
            assertEquals(
                "$version/$variant",
                memberDump(stock, CANCEL_SITE),
                memberDump(rewritten, CANCEL_SITE),
            )
        }
    }

    @Test
    fun `the rewritten class verifies`() {
        for ((version, variant) in allRecipeVariants()) {
            ClassReader(RealCallRewriter.rewrite(realCallStock(version, variant)))
                .accept(object : ClassVisitor(Opcodes.ASM9) {}, ClassReader.EXPAND_FRAMES)
        }
    }

    @Test
    fun `a renamed EventListener call fails closed naming the instruction`() {
        val tampered = tamperCancel("5.5.0", Variant.JVM) { insn ->
            object : MethodVisitor(Opcodes.ASM9, insn) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    val renamed = if (owner == "okhttp3/EventListener" && name == "canceled") {
                        "canceledTampered"
                    } else {
                        name
                    }
                    super.visitMethodInsn(opcode, owner, renamed, descriptor, isInterface)
                }
            }
        }
        val failure = assertFailsClosed { RealCallRewriter.rewrite(tampered) }
        assertTrue(failure, failure.contains(CANCEL_SITE_LABEL))
        assertTrue(failure, failure.contains("refusing to rewrite"))
        assertTrue(
            failure,
            failure.contains(
                "instruction 35: expected INVOKEVIRTUAL okhttp3/EventListener.canceled " +
                    "(Lokhttp3/Call;)V, found INVOKEVIRTUAL okhttp3/EventListener.canceledTampered " +
                    "(Lokhttp3/Call;)V",
            ),
        )
    }

    @Test
    fun `a reordered cancel body fails closed naming the instruction`() {
        // Swap the Exchange.cancel() call for a POP, so the body no longer matches the pin.
        val tampered = tamperCancel("5.4.0", Variant.ANDROID) { insn ->
            object : MethodVisitor(Opcodes.ASM9, insn) {
                private var dropped = false
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    if (!dropped && owner == "okhttp3/internal/connection/Exchange") {
                        dropped = true
                        super.visitInsn(Opcodes.POP)
                        return
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }
            }
        }
        val failure = assertFailsClosed { RealCallRewriter.rewrite(tampered) }
        assertTrue(
            failure,
            failure.contains(
                "instruction 11: expected INVOKEVIRTUAL okhttp3/internal/connection/Exchange.cancel " +
                    "()V, found POP",
            ),
        )
    }

    @Test
    fun `a missing cancel method fails closed`() {
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
        val failure = assertFailsClosed { RealCallRewriter.rewrite(writer.toByteArray()) }
        assertTrue(failure, failure.contains("cancel()V not found in okhttp3/internal/connection/RealCall"))
    }

    @Test
    fun `a class other than RealCall is rejected`() {
        val failure = assertFailsClosed { RealCallRewriter.rewrite(stock("5.5.0", Variant.JVM)) }
        assertTrue(failure, failure.contains("expected class okhttp3/internal/connection/RealCall"))
    }
}

private const val CANCEL_SITE = "cancel()V"
private const val CANCEL_SITE_LABEL = "cancel()V in okhttp3/internal/connection/RealCall"

private fun tamperCancel(version: String, variant: Variant, mutate: (MethodVisitor) -> MethodVisitor): ByteArray =
    tamperMethod(
        realCallStock(version, variant),
        InstrumentationTargets.CANCEL_NAME,
        InstrumentationTargets.CANCEL_DESC,
        mutate,
    )