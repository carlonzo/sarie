package sarie.plugin

/**
 * Stock shape of `okhttp3.internal.connection.RealCall.cancel()V`, identical on okhttp-android
 * and okhttp-jvm for 5.4.0 and 5.5.0 (ASM instruction dump of the pinned artifacts).
 *
 * The whole instruction stream is pinned, not a prefix: the rewrite appends
 * `sarie/bridge/CronetBridge.notifyCanceled` to the single terminal `RETURN`, so any drift in
 * the body - a reordered null check, a dropped listener call, an extra local - moves the
 * insertion point or removes it entirely. The guard therefore compares the stream in full and
 * fails closed; do not widen it to admit a new shape.
 *
 * The early `RETURN` (already-canceled fast path) is deliberately NOT rewritten: the registry
 * entry is gone after the first cancel, so a second `cancel()` must not notify again.
 */
internal object RealCallCancelGuard {

    /** javap-style instruction stream, labels numbered in first-encounter order. */
    val EXPECTED: List<String> = listOf(
        "ALOAD 0",
        "GETFIELD okhttp3/internal/connection/RealCall.canceled Z",
        "IFEQ L0",
        "RETURN",
        "ALOAD 0",
        "ICONST_1",
        "PUTFIELD okhttp3/internal/connection/RealCall.canceled Z",
        "ALOAD 0",
        "GETFIELD okhttp3/internal/connection/RealCall.exchange Lokhttp3/internal/connection/Exchange;",
        "DUP",
        "IFNULL L1",
        "INVOKEVIRTUAL okhttp3/internal/connection/Exchange.cancel ()V",
        "GOTO L2",
        "POP",
        "ALOAD 0",
        "GETFIELD okhttp3/internal/connection/RealCall.plansToCancel Ljava/util/concurrent/CopyOnWriteArrayList;",
        "INVOKEVIRTUAL java/util/concurrent/CopyOnWriteArrayList.iterator ()Ljava/util/Iterator;",
        "DUP",
        "LDC iterator(...)",
        "INVOKESTATIC kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue " +
            "(Ljava/lang/Object;Ljava/lang/String;)V",
        "ASTORE 1",
        "ALOAD 1",
        "INVOKEINTERFACE java/util/Iterator.hasNext ()Z",
        "IFEQ L3",
        "ALOAD 1",
        "INVOKEINTERFACE java/util/Iterator.next ()Ljava/lang/Object;",
        "CHECKCAST okhttp3/internal/connection/RoutePlanner\$Plan",
        "ASTORE 2",
        "ALOAD 2",
        "INVOKEINTERFACE okhttp3/internal/connection/RoutePlanner\$Plan.cancel ()V",
        "GOTO L4",
        "ALOAD 0",
        "GETFIELD okhttp3/internal/connection/RealCall.eventListener Lokhttp3/EventListener;",
        "ALOAD 0",
        "CHECKCAST okhttp3/Call",
        "INVOKEVIRTUAL okhttp3/EventListener.canceled (Lokhttp3/Call;)V",
        "RETURN",
    )

    fun verify(insns: List<String>): List<String> {
        val problems = mutableListOf<String>()
        for (index in 0 until maxOf(insns.size, EXPECTED.size)) {
            val actual = insns.getOrNull(index)
            val expected = EXPECTED.getOrNull(index)
            if (actual == expected) continue
            problems += "instruction $index: expected ${expected ?: "<end of method>"}, " +
                "found ${actual ?: "<end of method>"}"
        }
        return problems
    }
}