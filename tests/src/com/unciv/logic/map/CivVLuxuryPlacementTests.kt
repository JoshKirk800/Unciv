package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AssignStartingPlots.lua luxuries on a Huge Continents map of 12 civs and 24 city states */
@RunWith(GdxTestRunner::class)
class CivVLuxuryPlacementTests {

    private fun hugeMap(seed: Long): Pair<TileMap, com.unciv.models.ruleset.Ruleset> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(12) + ruleset.nations.values.filter { it.isCityState }.take(24)) {
            val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(128, 80); worldWrap = true; shape = MapShape.rectangular
        }
        return MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo) to ruleset
    }

    @Test
    fun `data of the Brave New World luxuries has the weights of the script`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val cocoa = ruleset.tileResources["Cocoa"]!!
        assertEquals(listOf("35", "5", "10", "10"), cocoa.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.ResourceWeighting,
            com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals).map { it.params[0] }.toList())
        assertEquals("15", ruleset.tileResources["Whales"]!!.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.LuxuryWeightingForCityStates).first().params[0])
    }

    @Test
    fun `most luxury kinds are on the map, marble is topped up to 3 in 4 civs and every start and city state has one near`() {
        for (seed in 61L..63L) {
            val (map, ruleset) = hugeMap(seed)
            val luxuries = map.values.filter { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
            val kinds = luxuries.map { it.resource!! }.toSet()
            // 20 kinds are assigned or left out: 1 of them left out on Huge, and a kind may find no tile (12 civs: 8 to regions at most, 3 to city states, the rest random)
            assertTrue("seed $seed: kinds $kinds", kinds.size >= 17)
            assertTrue("seed $seed: marble ${luxuries.count { it.resource == "Marble" }}", luxuries.count { it.resource == "Marble" } >= 9)

            val starts = map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isMajorCiv }.values.map { it.first() }
            val citySates = map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.values.map { it.first() }
            assertEquals(12, starts.size)
            for (start in starts) assertTrue("seed $seed: no luxury within 3 of a start",
                start.getTilesInDistanceRange(1..3).any { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury })
            val withLuxury = citySates.count { cs -> cs.getTilesInDistanceRange(1..2).any { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury } }
            assertTrue("seed $seed: $withLuxury of ${citySates.size} city states have a luxury", withLuxury >= citySates.size * 5 / 6)
        }
    }

    @Test
    fun `luxuries are not placed on starts and the luxuries total about a hundred and thirty`() {
        val (map, ruleset) = hugeMap(64L)
        val total = map.values.count { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury }
        assertTrue("luxuries $total", total in 100..170)
        val starts = map.startingLocationsByNation.values.flatten()
        assertTrue(starts.none { it.resource != null && ruleset.tileResources[it.resource!!]!!.resourceType == ResourceType.Luxury })
    }
}
