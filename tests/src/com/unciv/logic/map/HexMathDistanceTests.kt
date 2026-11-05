package com.unciv.logic.map

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import com.unciv.testing.BaseTestRunner

/**
 * [HexMath.getDistance] against an independent oracle: the number of steps through the six neighbor vectors
 * (the clock positions of [HexMath.getClockPositionToHexcoord]), found by breadth-first search.
 */
@RunWith(BaseTestRunner::class)
class HexMathDistanceTests {
    private val neighborVectors = listOf(2, 4, 6, 8, 10, 12).map { HexMath.getClockPositionToHexcoord(it) }
        .map { it.x to it.y }

    private fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>) = HexMath.getDistance(a.first, a.second, b.first, b.second)

    /** Steps from [origin] to every cell reachable within [maxSteps], by BFS over the neighbor vectors. */
    private fun bfs(origin: Pair<Int, Int>, maxSteps: Int): Map<Pair<Int, Int>, Int> {
        val steps = hashMapOf(origin to 0)
        var frontier = listOf(origin)
        for (step in 1..maxSteps) {
            val next = mutableListOf<Pair<Int, Int>>()
            for ((x, y) in frontier) for ((dx, dy) in neighborVectors) {
                val cell = x + dx to y + dy
                if (steps.putIfAbsent(cell, step) == null) next += cell
            }
            frontier = next
        }
        return steps
    }

    @Test
    fun distanceEqualsTheNumberOfNeighborSteps() {
        val maxSteps = 8
        for (origin in listOf(0 to 0, 3 to -2, -4 to 5, 7 to 7)) {
            val steps = bfs(origin, maxSteps)
            for (x in -20..20) for (y in -20..20) {
                val actual = dist(origin, x to y)
                val expected = steps[x to y]
                if (expected != null)
                    Assert.assertEquals("from $origin to ${x to y}", expected, actual)
                else
                    Assert.assertTrue("from $origin to ${x to y} is more than $maxSteps steps, but was $actual", actual > maxSteps)
            }
        }
    }

    @Test
    fun neighborsAreOneApartAndTheCellItselfIsZero() {
        for (x in -3..3) for (y in -3..3) {
            Assert.assertEquals(0, dist(x to y, x to y))
            for ((dx, dy) in neighborVectors)
                Assert.assertEquals("$x,$y to its neighbor", 1, dist(x to y, x + dx to y + dy))
        }
    }

    @Test
    fun knownValues() {
        Assert.assertEquals(3, dist(0 to 0, 3 to 3))      // along the diagonal
        Assert.assertEquals(4, dist(0 to 0, 2 to -2))     // against it: both axes count
        Assert.assertEquals(5, dist(-2 to 1, 3 to 2))
        Assert.assertEquals(6, dist(0 to 0, -6 to 0))
    }

    @Test
    fun distanceIsSymmetricAndObeysTheTriangleInequality() {
        val cells = (-4..4).flatMap { x -> (-4..4).map { y -> x to y } }
        for (a in cells) for (b in cells) {
            Assert.assertEquals("symmetry $a $b", dist(a, b), dist(b, a))
        }
        for (a in cells.filterIndexed { i, _ -> i % 7 == 0 })
            for (b in cells.filterIndexed { i, _ -> i % 5 == 0 })
                for (c in cells.filterIndexed { i, _ -> i % 3 == 0 })
                    Assert.assertTrue("triangle $a $b $c", dist(a, c) <= dist(a, b) + dist(b, c))
    }

    @Test
    fun overloadsAgree() {
        val a = HexCoord.of(-2, 5)
        val b = HexCoord.of(4, -1)
        Assert.assertEquals(dist(-2 to 5, 4 to -1), HexMath.getDistance(a, b))
        Assert.assertEquals(HexMath.getDistance(a, b), HexMath.getDistance(a.toVector2(), b.toVector2()))
    }
}
