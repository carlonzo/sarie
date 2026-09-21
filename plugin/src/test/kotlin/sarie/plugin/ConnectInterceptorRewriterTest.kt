package sarie.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ConnectInterceptorRewriterTest {

    @Test
    fun `rewritten intercept is exactly the trampoline for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val rewritten = ConnectInterceptorRewriter.rewrite(stock(version, variant))
            assertEquals("$version/$variant", expectedTrampoline, interceptInstructions(rewritten))
        }
    }

    @Test
    fun `all other members identical to stock for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val golden = stock(version, variant)
            val rewritten = ConnectInterceptorRewriter.rewrite(golden)
            assertEquals("$version/$variant", memberDump(golden), memberDump(rewritten))
        }
    }

    @Test
    fun `rewritten output re-parses cleanly for every recipe variant`() {
        for ((version, variant) in allRecipeVariants()) {
            val rewritten = ConnectInterceptorRewriter.rewrite(stock(version, variant))
            // EXPAND_FRAMES forces full parsing of the recomputed StackMapTable.
            ClassReader(rewritten).accept(EmptyVisitor, ClassReader.EXPAND_FRAMES)
            assertEquals("okhttp3/internal/connection/ConnectInterceptor", ClassReader(rewritten).className)
        }
    }

    @Test
    fun `wrong class name fails closed`() {
        val other = minimalClassBytes("com/example/NotConnectInterceptor")
        assertThrows(IllegalStateException::class.java) { ConnectInterceptorRewriter.rewrite(other) }
    }

    @Test
    fun `missing intercept method fails closed`() {
        val noIntercept = minimalClassBytes("okhttp3/internal/connection/ConnectInterceptor")
        assertThrows(IllegalStateException::class.java) { ConnectInterceptorRewriter.rewrite(noIntercept) }
    }
}

private object EmptyVisitor : ClassVisitor(Opcodes.ASM9)

private fun minimalClassBytes(internalName: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    writer.visitEnd()
    return writer.toByteArray()
}
