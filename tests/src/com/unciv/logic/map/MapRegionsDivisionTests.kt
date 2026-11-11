package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.getTileFertility
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots:DivideIntoRegions / ChopIntoTwoRegions on a map of one landmass with the same terrain on every tile */
@RunWith(GdxTestRunner::class)
class MapRegionsDivisionTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 20, newWidth = 20)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
    }

    private fun divide(numRegions: Int): MapRegions {
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, numRegions)
        return regions
    }

    @Test
    fun `every land tile is in a region, for any number of civs`() {
        for (civs in listOf(2, 3, 4, 5, 7, 11, 12, 13, 19, 22, 23)) {
            val regions = divide(civs).regions
            assertEquals("regions for $civs civs", civs, regions.size)
            assertEquals("tiles in a region for $civs civs", game.tileMap.values.count(), regions.flatMap { it.tiles }.toSet().size)
        }
    }

    @Test
    fun `a region as tall as wide is cut along its columns`() {
        // The map is 20 columns wide and 20 rows high; the original cuts along the rows only if the region is taller than wide
        val (first, second) = divide(2).regions
        assertEquals(20f, first.rect.width + second.rect.width)
        assertTrue(first.rect.width < first.rect.height)
        assertTrue(second.rect.width < second.rect.height)
    }

    @Test
    fun `the first part ends at the first column that reaches its share of the fertility`() {
        val (first, second) = divide(2).regions
        val total = first.totalFertility + second.totalFertility
        val share = total * 49.5 / 100
        assertTrue("the first part has ${first.totalFertility} of $total", first.totalFertility >= share)
        val lastColumn = first.tiles.maxOf { it.getColumn() }  // the part is cut on the left, the column order is the same
        val withoutLastColumn = first.tiles.filter { it.getColumn() != lastColumn }.sumOf { it.getTileFertility(true) }
        assertTrue("without its last column the first part has $withoutLastColumn", withoutLastColumn < share)
    }
}
