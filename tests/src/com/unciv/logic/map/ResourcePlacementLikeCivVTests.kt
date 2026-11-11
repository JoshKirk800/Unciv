package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.MapRegionResources
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots:PlaceStrategicAndBonusResources: what the Brave New World rules put on a generated map */
@RunWith(GdxTestRunner::class)
class ResourcePlacementLikeCivVTests {

    @Test
    fun `featureless plains and grassland get major deposits of horses`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 30, newWidth = 30)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Plains")
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, null, game.ruleset)

        val placed = MapRegionResources.placeMajorDeposits(
            tileData, game.ruleset, game.tileMap.values.toList().shuffled(java.util.Random(1)),
            game.ruleset.terrains["Plains"]!!, fallbackWeightings = false, baseImpact = 2, randomImpact = 2)

        // Horses is the only strategic resource weighted on featureless plains
        assertEquals(setOf("Horses"), placed.filterValues { it > 0 }.keys.map { it.name }.toSet())
        // one in 33 plains
        assertTrue("${placed.values.sum()} deposits", placed.values.sum() in 5..(900 / 33 + 1))
    }

    /** Samples of a generated Two Continents map of the size of the reference game */
    @Test
    fun `a generated map has no archaeological sites and a horse for every civ and then some`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (nation in ruleset.nations.values.filter { it.isMajorCiv }.take(6) + ruleset.nations.values.filter { it.isCityState }.take(12)) {
            val civ = Civilization(nation)
            civ.gameInfo = gameInfo
            gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            seed = 61L
            type = MapType.twoContinents
            shape = MapShape.rectangular
            mapSize = MapSize(80, 50)
            worldWrap = true
            waterThreshold = 0.1f
        }
        val map = MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo)

        val counts = map.values.mapNotNull { it.resource }.groupingBy { it }.eachCount()
        // They are placed when Archaeology is triggered (ArchaeologyFunctions.spawnSites), not by the map generator
        assertEquals(0, counts["Antiquity site"] ?: 0)
        assertEquals(0, counts["Hidden Antiquity site"] ?: 0)
        // 1 in 33 of the featureless plains and grassland, more than 1 per civ on a map this size
        assertTrue("horses ${counts["Horses"]}", (counts["Horses"] ?: 0) >= 6)
        // Bonus resources were 1.5 to 4 times too many because every bonus resource was also placed by the fallback rule for rulesets without frequencies
        val land = map.values.count { it.isLand }
        val bonuses = map.values.count { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Bonus && it.isLand }
        assertTrue("$bonuses bonus resources on $land land tiles", bonuses < land / 10)
    }
}
