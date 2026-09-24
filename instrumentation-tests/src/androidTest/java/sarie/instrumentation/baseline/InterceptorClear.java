package sarie.instrumentation.baseline;

import java.util.List;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * OkHttp 5.5 marks {@code OkHttpClient.Builder.interceptors} internal in its Kotlin metadata,
 * so Kotlin cannot call it; the accessor is public bytecode and Java can.
 */
final class InterceptorClear {
    private InterceptorClear() {}

    static void clear(OkHttpClient.Builder builder) {
        List<Interceptor> interceptors = builder.interceptors();
        interceptors.clear();
    }
}
