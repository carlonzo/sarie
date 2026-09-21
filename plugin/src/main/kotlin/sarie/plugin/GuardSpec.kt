package sarie.plugin

/** One required invoke pattern: instruction prefix and how many times it must appear. */
data class InvokeRule(val insnPrefix: String, val count: Int)

/**
 * Declarative structural shape of the stock ConnectInterceptor.intercept body for one okhttp
 * version: CHECKCAST owner prefix + first-occurrence max index + occurrence-count range,
 * required invoke rules (prefix + exact count), and the terminating instruction. One registry
 * line per okhttp version; [verify] is lifted verbatim from the original hardcoded
 * verifyStockShape (see the per-variant stock.txt golden dumps in the test resources).
 */
data class GuardSpec(
    val checkcastOwner: String,
    val checkcastFirstMaxIndex: Int,
    val checkcastCount: IntRange,
    val requiredInvokes: List<InvokeRule>,
    val mustEndWith: String,
)

/** Returns the shape problems (empty list = the instruction stream matches this spec). */
fun GuardSpec.verify(insns: List<String>): List<String> {
    val problems = mutableListOf<String>()
    val checkcast = "CHECKCAST $checkcastOwner"
    val castIndex = insns.indexOf(checkcast)
    if (castIndex !in 0..checkcastFirstMaxIndex) {
        problems += "expected $checkcast within the first ${checkcastFirstMaxIndex + 1} instructions"
    }
    if (insns.count { it == checkcast } !in checkcastCount) {
        problems += if (checkcastCount == 1..1) "expected exactly one $checkcast"
        else "expected $checkcast count within $checkcastCount"
    }
    for ((insnPrefix, count) in requiredInvokes) {
        if (insns.count { it.startsWith(insnPrefix) } != count) {
            val name = insnPrefix.substringAfter(' ').substringBefore(' ').substringAfterLast('/')
            val countWord = if (count == 1) "one" else count.toString()
            problems += "expected exactly $countWord $name call" + if (count == 1) "" else "s"
        }
    }
    if (insns.lastOrNull() != mustEndWith) problems += "expected method to end with $mustEndWith"
    return problems
}
