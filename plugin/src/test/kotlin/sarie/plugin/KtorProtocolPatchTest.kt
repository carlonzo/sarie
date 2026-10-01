package sarie.plugin

import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.android.build.api.instrumentation.InstrumentationParameters
import okhttp3.Protocol
import org.gradle.api.provider.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

class KtorProtocolPatchTest {

    /** 2.0.0 has no NoSuchFieldError handlers; the others do. All are distinct classfiles. */
    private val legacy = listOf("2.0.0", "2.2.4", "2.3.13", "3.0.0", "3.2.4")

    @Test
    fun `legacy Ktor maps HTTP_3 to the QUIC case`() {
        for (version in legacy) {
            val mapping = loadMapping(instrument(ktorGolden(version)))
            assertEquals("Ktor $version", expectedLegacy() + (Protocol.HTTP_3 to 6), mapping)
        }
    }

    @Test
    fun `Ktor 3_3 is left semantically unchanged`() {
        val mapping = loadMapping(instrument(ktorGolden("3.3.0")))
        assertEquals(expectedLegacy() + (Protocol.HTTP_3 to 7), mapping)
    }

    @Test
    fun `missing QUIC store fails closed`() {
        assertRejected { insns -> insns.first { it is FieldInsnNode && it.name == "QUIC" }.let { (it as FieldInsnNode).name = "TAMPERED" } }
    }

    @Test
    fun `wrong constant fails closed`() {
        assertRejected { insns ->
            val quic = insns.first { it is FieldInsnNode && it.name == "QUIC" }
            insns.set(quic.next.next, InsnNode(Opcodes.ICONST_5))
        }
    }

    @Test
    fun `duplicate store fails closed`() {
        assertRejected { insns -> insns.insertBefore(trailingLoad(insns), store("HTTP_1_0", Opcodes.ICONST_1)) }
    }

    @Test
    fun `extra store fails closed`() {
        assertRejected { insns -> insns.insertBefore(trailingLoad(insns), store("HTTP_3", Opcodes.ICONST_0)) }
    }

    @Test
    fun `missing clinit fails closed`() {
        val node = node(ktorGolden("2.3.13"))
        node.methods.removeIf { it.name == "<clinit>" }
        assertThrows(IllegalStateException::class.java) { instrument(write(node)) }
    }

    @Test
    fun `isInstrumentable true only for exact class name`() {
        val factory = object : KtorProtocolPatchFactory() {
            override val parameters: Property<InstrumentationParameters.None>
                get() = throw UnsupportedOperationException()
            override val instrumentationContext: InstrumentationContext
                get() = throw UnsupportedOperationException()
        }
        assertTrue(factory.isInstrumentable(fakeClassData(KtorProtocolPatch.TARGET_CLASS_DOT)))
        for (other in listOf(
            "io.ktor.client.engine.okhttp.OkUtilsKt",
            "io.ktor.client.engine.okhttp.OkHttp",
            "okhttp3.internal.connection.ConnectInterceptor",
            "com.example.WhenMappings",
            "",
        )) {
            assertFalse(other, factory.isInstrumentable(fakeClassData(other)))
        }
    }

    private fun assertRejected(tamper: (InsnList) -> Unit) {
        for (version in legacy) {
            val node = node(ktorGolden(version))
            tamper(node.methods.first { it.name == "<clinit>" }.instructions)
            val e = assertThrows(IllegalStateException::class.java) { instrument(write(node)) }
            assertTrue(e.message, e.message!!.contains("refusing to rewrite"))
        }
    }
}

private fun expectedLegacy(): Map<Protocol, Int> = mapOf(
    Protocol.HTTP_1_0 to 1,
    Protocol.HTTP_1_1 to 2,
    Protocol.SPDY_3 to 3,
    Protocol.HTTP_2 to 4,
    Protocol.H2_PRIOR_KNOWLEDGE to 5,
    Protocol.QUIC to 6,
)

/** Runs the same class visitor AGP uses, writing with computed frames like AGP does. */
private fun instrument(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    ClassReader(bytes).accept(KtorProtocolPatchClassVisitor(writer), 0)
    return writer.toByteArray()
}

/** Defines the class in an isolated loader (Ktor is not on the test classpath), runs <clinit>, reads the table. */
private fun loadMapping(bytes: ByteArray): Map<Protocol, Int> {
    val loader = object : ClassLoader(KtorProtocolPatchTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> =
            if (name == KtorProtocolPatch.TARGET_CLASS_DOT) defineClass(name, bytes, 0, bytes.size)
            else super.findClass(name)
    }
    val table = Class.forName(KtorProtocolPatch.TARGET_CLASS_DOT, true, loader)
        .getField("\$EnumSwitchMapping\$0").get(null) as IntArray
    return Protocol.entries.filter { table[it.ordinal] != 0 }.associateWith { table[it.ordinal] }
}

private fun node(bytes: ByteArray): ClassNode = ClassNode().also { ClassReader(bytes).accept(it, 0) }

private fun write(node: ClassNode): ByteArray =
    ClassWriter(ClassWriter.COMPUTE_FRAMES).also { node.accept(it) }.toByteArray()

private fun trailingLoad(insns: InsnList) = insns.last { it.opcode == Opcodes.ALOAD }

private fun store(protocol: String, const: Int) = InsnList().apply {
    add(VarInsnNode(Opcodes.ALOAD, 0))
    add(FieldInsnNode(Opcodes.GETSTATIC, "okhttp3/Protocol", protocol, "Lokhttp3/Protocol;"))
    add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "okhttp3/Protocol", "ordinal", "()I", false))
    add(InsnNode(const))
    add(InsnNode(Opcodes.IASTORE))
}

private fun ktorGolden(version: String): ByteArray =
    checkNotNull(KtorProtocolPatchTest::class.java.getResourceAsStream("/ktor/$version/OkUtilsKt\$WhenMappings.class")) {
        "missing golden /ktor/$version/OkUtilsKt\$WhenMappings.class; run plugin/scripts/extract-ktor-goldens.sh"
    }.readBytes()

private fun fakeClassData(className: String): ClassData = object : ClassData {
    override val className: String = className
    override val classAnnotations: List<String> = emptyList()
    override val interfaces: List<String> = emptyList()
    override val superClasses: List<String> = emptyList()
}
