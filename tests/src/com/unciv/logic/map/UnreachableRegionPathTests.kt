package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The classic pathfinder: an AI unit whose search found no path to one tile does not search again for other tiles outside what it reached */
@RunWith(BaseTestRunner::class)
class UnreachableRegionPathTests {
    private val game = TestGame()

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = false
        game.makeHexagonalMap(6)
        // a strip of ocean across the map: the unit stands on the west side and cannot cross it
        for (tile in game.tileMap.values.toList())
            if (tile.position.x == 0) game.setTileTerrain(tile.position, Constants.ocean)
    }

    private fun unitOnTheWest(): com.unciv.logic.map.mapunit.MapUnit {
        val civ = game.addCiv()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        val west = game.tileMap.values.first { it.position.x < 0 && it.isLand }
        return game.addUnit("Warrior", civ, west)
    }

    @Test
    fun `tiles across the water stay unreachable and tiles on the same side stay reachable, whatever was asked first`() {
        val unit = unitOnTheWest()
        val east = game.tileMap.values.filter { it.position.x > 0 }.take(5)
        val sameSide = game.tileMap.values.first { it.position.x < 0 && it.isLand && it != unit.getTile() && it.aerialDistanceTo(unit.getTile()) >= 2 }

        for (tile in east) assertTrue("no path to $tile", unit.movement.getShortestPath(tile).isEmpty())
        val path = unit.movement.getShortestPath(sameSide)
        assertTrue("a path to the tile on this side", path.isNotEmpty())
        assertEquals(sameSide, path.last())
    }

    @Test
    fun `after the unit has moved the earlier answer is not reused`() {
        val unit = unitOnTheWest()
        val east = game.tileMap.values.first { it.position.x > 0 }
        assertTrue(unit.movement.getShortestPath(east).isEmpty())

        // a bridge: the ocean tile between becomes land, then the destination is reachable
        for (tile in game.tileMap.values.toList()) if (tile.position.x == 0) game.setTileTerrain(tile.position, Constants.grassland)
        unit.movement.clearPathfindingCache()
        assertTrue("path after the terrain changed and the cache was cleared", unit.movement.getShortestPath(east).isNotEmpty())
    }
}
