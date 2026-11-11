package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** MapGenerator.lua GenerateCoasts, on a map of an ocean and a continent */
@RunWith(GdxTestRunner::class)
class CoastExpansionTests {

    @Test
    fun `coast reaches 3 tiles from land and 7 in 16 of the tiles 2 away are coast`() {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply {
            seed = 5L
            type = MapType.pangaea
            shape = MapShape.rectangular
            mapSize = MapSize(100, 60)
            worldWrap = true
        }
        val map = MapGenerator(ruleset).generateMap(params)
        // Distance of every water tile from land, lakes aside
        val seas = map.values.filter { it.isWater && it.baseTerrain != "Lakes" }
        val byDistance = seas.groupBy { tile -> (1..6).firstOrNull { d -> tile.getTilesAtDistance(d).any { it.isLand } } ?: 7 }
        assertTrue("every sea tile next to land is coast", byDistance[1]!!.all { it.baseTerrain == "Coast" })
        assertTrue("no coast 4 or more tiles from land", seas.none { it.baseTerrain == "Coast" && (byDistance[4]?.contains(it) == true || byDistance[5]?.contains(it) == true || byDistance[6]?.contains(it) == true || byDistance[7]?.contains(it) == true) })
        val second = byDistance[2]!!.count { it.baseTerrain == "Coast" }.toFloat() / byDistance[2]!!.size
        // Each of the two passes makes 1 in 4 of them coast: 1 - (3/4)^2. The generic rule gave 7 in 8.
        assertEquals(0.4375f, second, 0.08f)
    }
}
