package com.unciv.logic.map

import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A map with [MapParameters.civVGridWrap] on Civ V's pointy-hex grid: a Civ V plot (x, y) is the Unciv tile at (x + (y + 1) / 2, y)
 * and the world wraps by (width, 0). Against oracles that know only the offset-grid rule of Civ V's plots (odd rows lie half a plot to
 * the east) and a breadth-first search over those neighbours.
 */
@RunWith(BaseTestRunner::class)
class CivVGridWrapTests {
    private val width = 12
    private val height = 7
    private val game = TestGame()

    private fun hexOf(x: Int, y: Int) = HexCoord.of(x + (y + 1) / 2, y)

    private fun gridMap(wrap: Boolean = true): TileMap {
        val map = TileMap(width * height)
        map.mapParameters = MapParameters().apply {
            shape = MapShape.rectangular
            mapSize = MapSize(width, height)
            worldWrap = wrap
            civVGridWrap = true
        }
        for (y in 0 until height) for (x in 0 until width)
            map.tileList.add(Tile().apply { position = hexOf(x, y); baseTerrain = "Plains" })
        map.setTransients(game.ruleset)
        return map
    }

    /** The plots next to (x, y) by Civ V's rule: the same row east and west, and the rows above and below, whose odd ones lie half a plot east */
    private fun neighborsByTheOffsetRule(x: Int, y: Int, wrap: Boolean): Set<Pair<Int, Int>> {
        val shift = y and 1
        val candidates = listOf(x + 1 to y, x - 1 to y,
            x + shift to y + 1, x + shift - 1 to y + 1, x + shift to y - 1, x + shift - 1 to y - 1)
        return candidates.mapNotNull { (nx, ny) ->
            val wrapped = if (wrap) Math.floorMod(nx, width) else nx
            if (ny in 0 until height && wrapped in 0 until width) wrapped to ny else null
        }.toSet()
    }

    private fun Tile.plot(map: TileMap): Pair<Int, Int> {
        val y = position.y
        return Math.floorMod(position.x - (y + 1) / 2, width) to y
    }

    @Test
    fun everyTileHasTheNeighborsOfItsPlot() {
        val map = gridMap()
        for (y in 0 until height) for (x in 0 until width) {
            val tile = map[hexOf(x, y)]
            Assert.assertEquals("neighbors of plot ($x, $y)", neighborsByTheOffsetRule(x, y, true), tile.neighbors.map { it.plot(map) }.toSet())
        }
    }

    @Test
    fun withoutWrapTheEdgeTilesHaveNoNeighborsAcrossTheSeam() {
        val map = gridMap(wrap = false)
        for (y in 0 until height) for (x in 0 until width) {
            val tile = map[hexOf(x, y)]
            Assert.assertEquals("neighbors of plot ($x, $y)", neighborsByTheOffsetRule(x, y, false), tile.neighbors.map { it.plot(map) }.toSet())
        }
    }

    @Test
    fun clockPositionsOfNeighborsAreOpposite() {
        val map = gridMap()
        val opposite = mapOf(2 to 8, 4 to 10, 6 to 12, 8 to 2, 10 to 4, 12 to 6)
        for (tile in map.tileList) for (neighbor in tile.neighbors) {
            val there = map.getNeighborTileClockPosition(tile, neighbor)
            Assert.assertNotEquals("${tile.plot(map)} to ${neighbor.plot(map)}", -1, there)
            Assert.assertEquals("${tile.plot(map)} to ${neighbor.plot(map)}", opposite[there], map.getNeighborTileClockPosition(neighbor, tile))
            Assert.assertSame(neighbor, map.getClockPositionNeighborTile(tile, there))
        }
    }

    @Test
    fun distanceIsTheNumberOfStepsThroughTheWrap() {
        val map = gridMap()
        for (source in map.tileList) {
            val steps = HashMap<Tile, Int>().apply { put(source, 0) }
            val queue = ArrayDeque(listOf(source))
            while (queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                for (neighbor in tile.neighbors) if (steps.putIfAbsent(neighbor, steps[tile]!! + 1) == null) queue.addLast(neighbor)
            }
            for (tile in map.tileList)
                Assert.assertEquals("from ${source.plot(map)} to ${tile.plot(map)}", steps[tile], source.aerialDistanceTo(tile))
        }
    }

    @Test
    fun aRiverCrossesTheSeam() {
        val map = gridMap()
        val east = map[hexOf(width - 1, 3)]
        val west = map[hexOf(0, 3)]
        Assert.assertTrue(east.setConnectedByRiver(west, true))
        Assert.assertTrue(east.isConnectedByRiver(west))
        Assert.assertTrue(west.isConnectedByRiver(east))
        Assert.assertFalse(east.isConnectedByRiver(map[hexOf(width - 2, 3)]))
    }
}
