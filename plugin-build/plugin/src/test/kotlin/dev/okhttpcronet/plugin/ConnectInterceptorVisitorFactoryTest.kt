package dev.okhttpcronet.plugin

import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Factory-level exclusivity check through the real AGP ClassData interface; the visitor
 * behaviour itself is covered by [ConnectInterceptorGuardVisitorTest].
 */
class ConnectInterceptorVisitorFactoryTest {

    @Test
    fun `isInstrumentable is exclusive to the exact ConnectInterceptor class`() {
        val factory = factory()
        assertTrue(factory.isInstrumentable(fakeClassData("okhttp3.internal.connection.ConnectInterceptor")))
        for (other in listOf(
            "okhttp3.internal.connection.RetryAndFollowUpInterceptor",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.http.RealInterceptorChain",
            "okhttp3.OkHttpClient",
            "okhttp3.internal.connection.ConnectInterceptorKt",
            "com.example.Unrelated",
            "",
        )) {
            assertFalse(other, factory.isInstrumentable(fakeClassData(other)))
        }
    }
}

private fun factory(): ConnectInterceptorVisitorFactory = object : ConnectInterceptorVisitorFactory() {
    override val parameters: Property<InstrumentationParameters.None>
        get() = throw UnsupportedOperationException("not needed for isInstrumentable")
    override val instrumentationContext: InstrumentationContext
        get() = throw UnsupportedOperationException("not needed for isInstrumentable")
}

private fun fakeClassData(className: String): ClassData = object : ClassData {
    override val className: String = className
    override val classAnnotations: List<String> = emptyList()
    override val interfaces: List<String> = emptyList()
    override val superClasses: List<String> = emptyList()
}
