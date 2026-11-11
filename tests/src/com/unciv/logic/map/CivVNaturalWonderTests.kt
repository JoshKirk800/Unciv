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

@RunWith(GdxTestRunner::class)
class CivVNaturalWonderTests {
    private fun wonders(seed: Long, width: Int, height: Int, civs: Int): List<String> {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val gameInfo = GameInfo()
        gameInfo.ruleset = ruleset
        for (n in ruleset.nations.values.filter { it.isMajorCiv }.take(civs) + ruleset.nations.values.filter { it.isCityState }.take(civs * 2)) {
            val civ = Civilization(n); civ.gameInfo = gameInfo; gameInfo.civilizations.add(civ)
        }
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; mapSize = MapSize(width, height); worldWrap = true; shape = MapShape.rectangular
        }
        val map = MapGenerator(ruleset).generateMap(params, GameParameters(), gameInfo)
        return map.values.mapNotNull { it.naturalWonder }
    }

    @Test
    fun `a Huge map gets 7 wonders and a Standard one 5, as the scripts give by world size`() {
        for (seed in 81L..83L) {
            val huge = wonders(seed, 128, 80, 12)
            // The Great Barrier Reef is two tiles
            val hugeCount = huge.distinct().size
            assertEquals("seed $seed: ${huge.distinct()}", 7, hugeCount)
            val standard = wonders(seed, 80, 52, 8).distinct().size
            assertEquals("seed $seed", 5, standard)
            assertEquals("seed $seed", 2, wonders(seed, 40, 25, 3).distinct().size)
            assertEquals("seed $seed", 3, wonders(seed, 50, 32, 4).distinct().size)
            // 100 x 64 is Large: 6 (the radius formula gives 5 there)
            assertEquals("seed $seed", 6, wonders(seed, 100, 64, 10).distinct().size)
        }
    }
}
