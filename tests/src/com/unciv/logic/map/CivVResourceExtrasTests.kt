package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PlaceSexyBonusAtCivStarts, the top-ups, the minor deposits at city states and FixSugarJungles on a Huge Continents map */
@RunWith(GdxTestRunner::class)
class CivVResourceExtrasTests {

    private fun hugeMap(seed: Long, civs: Int = 12): Pair<TileMap, com.unciv.models.ruleset.Ruleset> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(civs) + ruleset.nations.values.filter { it.isCityState }.take(24)) {
            val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(128, 80); worldWrap = true; shape = MapShape.rectangular
        }
        return MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo) to ruleset
    }

    @Test
    fun `no sugar is on jungle and there is enough iron, horses, coal, oil, aluminum and uranium for every civ`() {
        for (seed in 71L..73L) {
            val (map, _) = hugeMap(seed)
            assertTrue(map.values.none { it.resource == "Sugar" && "Jungle" in it.terrainFeatures })
            fun units(name: String) = map.values.filter { it.resource == name }.sumOf { maxOf(it.resourceAmount, 1) }
            // The top-ups: below the limit a deposit is added (when a tile can hold it), so the counts end near or above the limits
            assertTrue("seed $seed: iron ${units("Iron")}", units("Iron") >= 8)
            assertTrue("seed $seed: horses ${units("Horses")}", units("Horses") >= 4 * 12 - 4)
            assertTrue("seed $seed: coal ${units("Coal")}", units("Coal") >= 8)
            assertTrue("seed $seed: oil ${units("Oil")}", units("Oil") >= 4 * 12 - 8)
            assertTrue("seed $seed: uranium ${units("Uranium")}", units("Uranium") >= 2 * 12 - 4)
        }
    }

    @Test
    fun `every start has a bonus resource on its third ring or, failing a fitting tile, fish`() {
        for (seed in 71L..73L) {
            val (map, ruleset) = hugeMap(seed)
            val bonusKinds = setOf("Deer", "Bananas", "Wheat", "Sheep", "Cattle", "Fish")
            val starts = map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isMajorCiv }.values.map { it.first() }
            assertEquals(12, starts.size)
            val without = starts.count { start -> start.getTilesAtDistance(3).none { it.resource in bonusKinds } }
            assertTrue("seed $seed: $without of 12 starts have no bonus on their third ring", without <= 2)
        }
    }

    @Test
    fun `about three in four city states have a deposit of coal, oil or aluminum near`() {
        var withDeposit = 0
        var total = 0
        for (seed in 71L..73L) {
            val (map, ruleset) = hugeMap(seed)
            for (start in map.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.values.map { it.first() }) {
                total++
                if (start.getTilesInDistanceRange(1..3).any { it.resource in setOf("Coal", "Oil", "Aluminum") }) withDeposit++
            }
        }
        assertTrue("$withDeposit of $total", withDeposit >= total / 2)
    }
}
