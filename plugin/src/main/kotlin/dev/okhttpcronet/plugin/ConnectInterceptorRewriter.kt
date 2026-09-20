package dev.okhttpcronet.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites exactly `okhttp3.internal.connection.ConnectInterceptor.intercept` into a trampoline
 * to [CronetBridge]; the original body (initExchange/copy/proceed) lives inside CronetBridge as
 * the fallback path. Everything else in the class (`<clinit>`, `INSTANCE`, ctor, metadata) is
 * copied through unchanged.
 */
object ConnectInterceptorRewriter {

    private const val TARGET_CLASS = "okhttp3/internal/connection/ConnectInterceptor"
    internal const val INTERCEPT_NAME = "intercept"
    internal const val INTERCEPT_DESC = "(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"
    internal const val BRIDGE_OWNER = "dev/okhttpcronet/bridge/CronetBridge"

    /**
     * Emits the trampoline body into [delegate]. Single source of the rewritten bytecodes,
     * shared with the AGP visitor in [ConnectInterceptorGuardVisitor].
     */
    internal fun emitTrampoline(delegate: MethodVisitor) {
        delegate.visitCode()
        delegate.visitVarInsn(Opcodes.ALOAD, 1)
        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, INTERCEPT_NAME, INTERCEPT_DESC, false)
        delegate.visitInsn(Opcodes.ARETURN)
        delegate.visitMaxs(1, 2)
    }

    fun rewrite(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        check(reader.className == TARGET_CLASS) {
            "okhttp-cronet: expected class $TARGET_CLASS, got ${reader.className}"
        }
        var found = false
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != INTERCEPT_NAME || descriptor != INTERCEPT_DESC) return delegate
                found = true
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitCode() {
                        emitTrampoline(delegate)
                    }

                    // Original body, frames, line numbers, locals and annotations are discarded.
                    override fun visitEnd() {
                        delegate.visitEnd()
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)
        check(found) {
            "okhttp-cronet: method $INTERCEPT_NAME$INTERCEPT_DESC not found in $TARGET_CLASS"
        }
        return writer.toByteArray()
    }
}
