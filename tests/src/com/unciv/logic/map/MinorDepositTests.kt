package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.StrategicBonusResourcePlacementLogic
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots:PlaceSmallQuantitiesOfStrategics(23, land_list): the small deposits of strategic resources */
@RunWith(GdxTestRunner::class)
class MinorDepositTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    @Test
    fun `the Civ V rulesets draw small deposits on flat land only, 23 tiles to a deposit`() {
        val constants = game.ruleset.modOptions.constants
        assertTrue(constants.minorDepositsOnFlatLandOnly)
        assertEquals(23, constants.minorDepositFrequency)
    }

    /** land_list holds the flat land tiles that have no feature but forest or jungle: no hills, mountains, marsh, flood plains or oases, and no start or resource */
    @Test
    fun `flat land with forest or jungle is a site for small deposits, hills marsh and mountains are not`() {
        game.makeRectangularMap(newHeight = 6, newWidth = 6)
        val tiles = game.tileMap.values.toList()
        fun site(terrain: String, vararg features: String): Boolean {
            val tile = tiles[0]
            game.setTileTerrainAndFeatures(tile.position, terrain, *features)
            tile.setTileResource(null as String?)
            return StrategicBonusResourcePlacementLogic.isFlatMinorDepositSite(tile, emptySet())
        }
        assertTrue(site("Plains"))
        assertTrue(site("Grassland", "Forest"))
        assertTrue(site("Plains", "Jungle"))
        assertTrue(site("Tundra"))
        assertTrue(site("Snow"))
        assertFalse(site("Plains", "Hill"))
        assertFalse(site("Grassland", "Hill", "Forest"))
        assertFalse(site("Plains", "Hill", "Jungle"))
        assertFalse(site("Grassland", "Marsh"))
        assertFalse(site("Plains", "Flood plains"))
        assertFalse(site("Desert", "Oasis"))
        assertFalse(site("Mountain"))

        val tile = tiles[0]
        game.setTileTerrainAndFeatures(tile.position, "Plains")
        assertFalse("start", StrategicBonusResourcePlacementLogic.isFlatMinorDepositSite(tile, setOf(tile)))
        tile.setTileResource(game.ruleset.tileResources["Horses"]!!, majorDeposit = true)
        assertFalse("resource", StrategicBonusResourcePlacementLogic.isFlatMinorDepositSite(tile, emptySet()))
    }

    private fun minorDepositsOnPlains(frequency: Int): Int {
        game.makeRectangularMap(newHeight = 30, newWidth = 30)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Plains")
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, null, game.ruleset)
        val strategic = game.ruleset.tileResources.values.filter { it.resourceType == ResourceType.Strategic }
        val totalPlaced = HashMap<TileResource, Int>().apply { strategic.forEach { put(it, 0) } }
        val landList = ArrayList(game.tileMap.values)

        StrategicBonusResourcePlacementLogic.placeMinorDepositsOnLand(frequency, 1f, landList, tileData, strategic, false, totalPlaced)

        val placed = game.tileMap.values.filter { it.resource != null }
        // Minor deposits, not major ones
        assertTrue(placed.all { it.resourceAmount == game.ruleset.tileResources[it.resource!!]!!.minorDepositAmount.default })
        assertEquals(placed.size, totalPlaced.values.sum())
        return placed.size
    }

    @Test
    fun `one small deposit is placed for every frequency tiles of the list, and one more`() {
        assertEquals(900 / 23 + 1, minorDepositsOnPlains(23))
        assertEquals(900 / 30 + 1, minorDepositsOnPlains(30))
    }
}
