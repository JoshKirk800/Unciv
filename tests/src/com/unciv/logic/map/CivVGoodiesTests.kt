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

/** AddGoodies: one ruin per 40 land tiles on each landmass, 3 apart, more than 3 from a start, never on a resource */
@RunWith(GdxTestRunner::class)
class CivVGoodiesTests {
    @Test
    fun `ruins follow the size of each landmass and keep their distances`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        for (seed in 91L..93L) {
            val gameInfo = GameInfo()
            gameInfo.ruleset = ruleset
            for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(12) + ruleset.nations.values.filter { it.isCityState }.take(24)) {
                val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
            }
            val params = MapParameters().apply {
                this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(128, 80); worldWrap = true; shape = MapShape.rectangular
            }
            val map = MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo)
            val ruins = map.values.filter { it.improvement == "Ancient ruins" }
            val starts = map.startingLocationsByNation.values.flatten()
            assertTrue(ruins.isNotEmpty())
            for (r in ruins) {
                assertTrue("seed $seed: ruin on a resource", r.resource == null)
                assertTrue("seed $seed: ruin near a start", starts.none { it.aerialDistanceTo(r) <= 3 })
                assertTrue("seed $seed: ruins close together", ruins.none { it != r && it.aerialDistanceTo(r) < 3 })
            }
            for ((continent, size) in map.continentSizes.filter { it.value >= 40 }) {
                val count = ruins.count { it.getContinent() == continent }
                val expected = (size + 20) / 40
                // A landmass can be short of tiles that keep the distances, never over the number
                assertTrue("seed $seed continent $continent of $size has $count ruins, wanted $expected", count in (expected - 1)..expected)
            }
        }
    }
}
