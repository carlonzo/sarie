package sarie.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    @Test
    fun emptyArrayReturnsZero() {
        assertEquals(0L, Stats.median(longArrayOf()))
        assertEquals(0L, Stats.percentile(longArrayOf(), 50.0))
    }

    @Test
    fun singleElement() {
        val data = longArrayOf(42L)
        assertEquals(42L, Stats.median(data))
        assertEquals(42L, Stats.percentile(data, 50.0))
        assertEquals(42L, Stats.percentile(data, 95.0))
    }

    @Test
    fun oddCountMedian() {
        val data = longArrayOf(30L, 10L, 20L)
        assertEquals(20L, Stats.median(data))
    }

    @Test
    fun evenCountMedian() {
        val data = longArrayOf(10L, 20L, 30L, 40L)
        assertEquals(25L, Stats.median(data))
    }

    @Test
    fun percentiles() {
        val data = LongArray(100) { it.toLong() + 1 }
        assertEquals(50L, Stats.percentile(data, 50.0))
        assertEquals(95L, Stats.percentile(data, 95.0))
        assertEquals(99L, Stats.percentile(data, 99.0))
        assertEquals(1L, Stats.percentile(data, 1.0))
    }

    @Test
    fun doesNotMutateOriginalArray() {
        val original = longArrayOf(5L, 2L, 8L)
        val copy = original.clone()
        Stats.median(original)
        assertArrayEquals(copy, original)
    }
}
