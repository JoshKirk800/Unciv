package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.StrategicBonusResourcePlacementLogic
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots:PlaceOilInTheSea: the oil in the sea is half of the units of oil placed so far, divided by 4 per deposit */
@RunWith(GdxTestRunner::class)
class SeaOilTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun place(landDeposits: Int): Pair<Int, List<com.unciv.logic.map.tile.Tile>> {
        game.makeRectangularMap(newHeight = 60, newWidth = 60, baseTerrain = "Coast")
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Coast")
        val oil = game.ruleset.tileResources["Oil"]!!
        val land = game.tileMap.values.filter { it.getColumn() % 2 == 0 && it.getRow() % 2 == 0 }.take(landDeposits)
        for (tile in land) {
            game.setTileTerrain(tile.position, "Tundra")
            tile.setTileResource(oil, majorDeposit = true)
        }
        val units = land.sumOf { it.resourceAmount }
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, null, game.ruleset)
        val totalPlaced = HashMap<TileResource, Int>().apply { put(oil, landDeposits) }
        StrategicBonusResourcePlacementLogic.placeSeaDepositsOfLandResource(game.ruleset, game.tileMap, tileData, totalPlaced)
        return units to game.tileMap.values.filter { it.isWater && it.resource == "Oil" }
    }

    @Test
    fun `the sea gets one deposit of 4 for every 8 units of oil on land`() {
        val (units, sea) = place(landDeposits = 16)
        assertEquals(7 * 16, units)
        assertEquals(units / 2 / 4, sea.size)
        assertTrue(sea.all { it.resourceAmount == 4 })
    }

    @Test
    fun `deposits in the sea are at least 5 tiles apart`() {
        val (_, sea) = place(landDeposits = 16)
        for (a in sea) for (b in sea) if (a != b) assertTrue(a.aerialDistanceTo(b) >= 5)
    }
}
