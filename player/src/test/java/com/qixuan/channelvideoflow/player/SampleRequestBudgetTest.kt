package com.qixuan.channelvideoflow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleRequestBudgetTest {
    @Test
    fun canceledAttemptAndSmallerBudgetNeverRefundPriorReservations() {
        val target = SampleRequestBudget()
        target.reserve(800L, 1_000L)
        assertThrows(IllegalArgumentException::class.java) { target.reserve(1L, 500L) }
        target.reserve(0L, 500L) // Already cached data is still readable.
        target.reserve(200L, 1_000L)
        assertThrows(IllegalArgumentException::class.java) { target.reserve(1L, 1_000L) }
        assertEquals(1_000L, target.reservedBytes)
    }

    @Test
    fun concurrentReadersShareOneTargetCeiling() {
        val target = SampleRequestBudget()
        val readers = List(8) {
            Thread {
                repeat(1_000) {
                    try { target.reserve(1L, 2_000L) } catch (_: IllegalArgumentException) { }
                }
            }.apply { start() }
        }
        readers.forEach(Thread::join)
        assertEquals(2_000L, target.reservedBytes)
    }

    @Test
    fun seededHandoverShrinksTheRemainingCeilingInsteadOfResettingIt() {
        // A target whose bytes were already requested by the lightweight stage must not be granted
        // the full ceiling again by the stage that takes over.
        val handedOver = SampleRequestBudget()
        handedOver.seed(256L * 1024L)
        assertEquals(256L * 1024L, handedOver.reservedBytes)

        handedOver.reserve(100L, 20L * 1024L * 1024L)
        assertEquals(256L * 1024L + 100L, handedOver.reservedBytes)
        // The pool may now spend only what the ceiling leaves, not the whole ceiling.
        assertThrows(IllegalArgumentException::class.java) {
            handedOver.reserve(20L * 1024L * 1024L, 20L * 1024L * 1024L)
        }

        // A seed larger than the remaining ceiling must still refuse new bytes rather than throw
        // from a negative allowance.
        val overSeeded = SampleRequestBudget()
        overSeeded.seed(2L * 1024L * 1024L)
        overSeeded.reserve(0L, 1L * 1024L * 1024L)
        assertThrows(IllegalArgumentException::class.java) { overSeeded.reserve(1L, 1L * 1024L * 1024L) }
    }
}
