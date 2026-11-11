package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.MinorCivPlacer
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.max

/** AssignStartingPlots:PlaceCityStateInRegion and ObtainNextSectionInRegion */
@RunWith(GdxTestRunner::class)
class CityStatePlacementTests {

    @Test
    fun `city states of a region are placed along its edge, between the civs`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 40, newWidth = 40, baseTerrain = "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 1)
        val region = regions.regions.single()
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, region, game.ruleset)
        val civs = (1..4).map { game.addCiv(cityStateType = "Cultured") }

        MinorCivPlacer.placeMinorCivs(regions.regions, game.tileMap, civs, tileData, game.ruleset)

        val starts = game.tileMap.startingLocationsByNation.values.flatten()
        assertEquals(4, starts.size)
        // The region is as wide as tall, so the left and right 16.7 % of it are searched first
        val band = ceil(0.167 * max(region.rect.width, region.rect.height)).toInt()
        for (start in starts) {
            val column = start.getColumn() - region.rect.x.toInt()
            assertTrue("city state in column $column of ${region.rect.width.toInt()}",
                column < band || column >= region.rect.width.toInt() - band)
        }
    }
}
