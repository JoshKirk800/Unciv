package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Continents.lua: the landmass of the Civ V map type */
@RunWith(GdxTestRunner::class)
class ContinentsLikeCivVTests {

    private fun generate(seed: Long): TileMap {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; shape = MapShape.rectangular
            mapSize = MapSize(100, 64); worldWrap = true
        }
        return MapGenerator(ruleset).generateMap(params)
    }

    @Test
    fun `the poles are sea, a quarter of the map is land and no landmass has more than 58 percent of it`() {
        for (seed in 1L..4L) {
            val map = generate(seed)
            val land = map.values.filter { it.isLand }
            val share = land.size / map.values.size.toDouble()
            assertTrue("seed $seed: land share $share", share in 0.15..0.40)
            val maxLat = map.maxLatitude
            val polar = land.count { abs(it.latitude) >= 0.9 * maxLat }
            assertTrue("seed $seed: $polar land tiles at the poles", polar <= land.size / 100)
            map.assignContinents(TileMap.AssignContinentsMode.Reassign)
            assertTrue("seed $seed: biggest landmass ${map.continentSizes.values.max()} of ${land.size}",
                map.continentSizes.values.max() <= land.size * 0.6)
        }
    }
}
