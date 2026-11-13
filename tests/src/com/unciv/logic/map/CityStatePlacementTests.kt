package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.MinorCivPlacer
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.badlogic.gdx.math.Rectangle
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

    /** CanPlaceCityStateAt only refuses water, mountains and snow: the desert of a region that is not a desert region is a site as good as any */
    @Test
    fun `city states may be placed on the desert of a region that is not a desert region`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 40, newWidth = 40)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Desert")
        // One tile that is not desert, in the middle of the map where no band of the region reaches before the last round
        val middleColumn = game.tileMap.values.let { tiles -> (tiles.minOf { it.getColumn() } + tiles.maxOf { it.getColumn() }) / 2 }
        val middleRow = game.tileMap.values.let { tiles -> (tiles.minOf { it.getRow() } + tiles.maxOf { it.getRow() }) / 2 }
        game.setTileTerrain(game.tileMap.values.first { it.getColumn() == middleColumn && it.getRow() == middleRow }.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 1)
        val region = regions.regions.single()
        region.type = "Hybrid"
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, region, game.ruleset)
        assertTrue("desert is undesirable outside a desert region", tileData[game.tileMap.values.first()]!!.isJunk)
        val civs = (1..4).map { game.addCiv(cityStateType = "Cultured") }

        MinorCivPlacer.placeMinorCivs(regions.regions, game.tileMap, civs, tileData, game.ruleset)

        val starts = game.tileMap.startingLocationsByNation.values.flatten()
        assertEquals(4, starts.size)
        val band = ceil(0.167 * max(region.rect.width, region.rect.height)).toInt()
        for (start in starts) {
            val column = start.getColumn() - region.rect.x.toInt()
            assertTrue("city state in column $column of ${region.rect.width.toInt()}",
                column < band || column >= region.rect.width.toInt() - band)
        }
    }

    private fun leftoverCityStateGoesTo(baseRuleset: BaseRuleset): Pair<Region, Region> {
        val game = TestGame(baseRuleset = baseRuleset)
        game.makeRectangularMap(newHeight = 40, newWidth = 40, baseTerrain = "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        // Several equal landmasses (an archipelago), so that no city state is sent to an uninhabited one
        game.tileMap.continentSizes.clear()
        for (c in 0..4) game.tileMap.continentSizes[c] = 100
        // Big: 100 land tiles, 1000 fertility (10 per tile). Small: 10 land tiles, 500 (50 per tile): less in total, more per tile
        val big = Region(game.tileMap, Rectangle(0f, 0f, 10f, 10f))
        val small = Region(game.tileMap, Rectangle(20f, 20f, 5f, 2f))
        big.tiles.addAll(game.tileMap.getTilesInRectangle(big.rect)); big.totalFertility = 1000
        small.tiles.addAll(game.tileMap.getTilesInRectangle(small.rect)); small.totalFertility = 500
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, big, game.ruleset)
        val civ = game.addCiv(cityStateType = "Cultured")
        MinorCivPlacer.placeMinorCivs(listOf(big, small), game.tileMap, listOf(civ), tileData, game.ruleset)
        return big to small
    }

    @Test
    fun `a city state left over goes to the region with the least fertility per land tile in Civ V, in total otherwise`() {
        val (big, small) = leftoverCityStateGoesTo(BaseRuleset.Civ_V_BNW)
        assertEquals("big region", 1, big.assignedMinorCivs.size)
        assertEquals("small region", 0, small.assignedMinorCivs.size)
    }
}
