package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.StartNormalizer
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots:NormalizeStartLocation and NormalizeCityState on a map of one terrain */
@RunWith(GdxTestRunner::class)
class CivVStartNormalizerTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    }

    private fun normalize(terrain: String, isMinorCiv: Boolean): Tile {
        game.makeRectangularMap(newHeight = 24, newWidth = 24, baseTerrain = terrain)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, terrain)
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val start = game.tileMap.values.first { it.getColumn() == 0 && it.getRow() == 0 }
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, null, game.ruleset)
        StartNormalizer.normalizeStart(start, game.tileMap, tileData, game.ruleset, isMinorCiv)
        return start
    }

    private fun Tile.ring(distance: Int) = getTilesAtDistance(distance).toList()
    private fun List<Tile>.resources() = mapNotNull { it.resource }.groupingBy { it }.eachCount()

    @Test
    fun `a start of dry plains gets two wheat in the first ring and one second ring tile turned to grass`() {
        // Every tile has two food and one hammer of its own: the food score is 18, the inner 6, and no tile has two food natively
        // beyond that, so two bonus resources and, as there is no native two-food tile, one grassland in the second ring
        val start = normalize("Plains", isMinorCiv = false)
        assertEquals(mapOf("Wheat" to 2), start.ring(1).resources())
        assertTrue(start.ring(2).resources().isEmpty())
        assertEquals(1, start.ring(2).count { it.baseTerrain == "Grassland" })
        assertEquals(0, start.ring(1).count { it.baseTerrain == "Grassland" })
    }

    @Test
    fun `a start of dry grassland gets a hill, and stone, one in the first and one in the second ring`() {
        // No hammers at all in the first ring: one first ring tile becomes a hill. 18 grass tiles and no plains: two stone
        val start = normalize("Grassland", isMinorCiv = false)
        assertEquals(1, start.ring(1).count { "Hill" in it.terrainFeatures })
        assertEquals(mapOf("Stone" to 1), start.ring(1).resources())
        assertEquals(mapOf("Stone" to 1), start.ring(2).resources())
    }

    @Test
    fun `a city state in dry grassland gets a hill and no stone`() {
        val start = normalize("Grassland", isMinorCiv = true)
        assertEquals(1, start.ring(1).count { "Hill" in it.terrainFeatures })
        assertTrue(start.ring(1).resources().isEmpty() && start.ring(2).resources().isEmpty())
    }
}
