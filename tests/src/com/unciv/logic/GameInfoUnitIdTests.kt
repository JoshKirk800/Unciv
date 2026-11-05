package com.unciv.logic

import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

@RunWith(BaseTestRunner::class)
class GameInfoUnitIdTests {

    @Test
    fun `unit ids are sequential`() {
        val gameInfo = GameInfo()
        Assert.assertEquals(listOf(1, 2, 3), List(3) { gameInfo.getNextUnitId() })
    }

    /** Duplicate unit ids have been seen in the wild; ids are handed out from several threads (AI, UI, background jobs). */
    @Test
    fun `concurrent callers never get the same unit id`() {
        val gameInfo = GameInfo()
        val threads = 8
        val perThread = 100_000
        val barrier = CyclicBarrier(threads)
        val results = Array(threads) { IntArray(perThread) }

        List(threads) { t ->
            thread {
                barrier.await()
                for (i in 0 until perThread) results[t][i] = gameInfo.getNextUnitId()
            }
        }.forEach { it.join() }

        val all = results.flatMap { it.asList() }
        Assert.assertEquals("every id handed out exactly once", all.size, all.toSet().size)
        Assert.assertEquals(threads * perThread, all.max())
    }
}
