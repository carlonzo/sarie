package sarie.demo

object Stats {

    fun median(values: LongArray): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.clone().apply { sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    fun percentile(values: LongArray, p: Double): Long {
        require(p in 0.0..100.0) { "Percentile must be between 0 and 100, got $p" }
        if (values.isEmpty()) return 0L
        val sorted = values.clone().apply { sort() }
        if (sorted.size == 1) return sorted[0]
        val index = Math.ceil(p / 100.0 * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }
}
