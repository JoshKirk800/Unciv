package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** An embarked unit has EMBARKED_VISIBILITY_RANGE (0) plus the sight of its promotions as its sight range (CvUnit::visibilityRange),
 *  and the ring of tiles around a unit of range 0 is still seen (CvPlot::changeAdjacentSight reaches one ring beyond the range). */
@RunWith(BaseTestRunner::class)
class EmbarkedSightTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var sea: Tile

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        for (tile in game.tileMap.tileList) {
            tile.baseTerrain = Constants.ocean
            tile.setTransients()
        }
        civ = game.addCiv()
        for (tech in game.ruleset.technologies.keys) civ.tech.addTechnology(tech) // lets land units embark on ocean
        sea = game.getTile(0, 0)
    }

    private fun embarked(name: String, owner: Civilization = civ): MapUnit =
        game.addUnit(name, owner, sea).also { assertTrue(it.isEmbarked()) }

    @Test
    fun `a plain embarked unit has a sight range of 0`() {
        assertEquals(0, embarked("Warrior").getVisibilityRange())
    }

    @Test
    fun `embarked Marines have the sight of the Embarked Sight promotion, 2`() {
        assertEquals(2, embarked("Marine").getVisibilityRange())
    }

    @Test
    fun `an embarked unit of Polynesia has the sight of War Canoes, 2`() {
        val polynesia = game.addCiv(game.ruleset.nations["Polynesia"]!!)
        for (tech in game.ruleset.technologies.keys) polynesia.tech.addTechnology(tech)
        assertEquals(2, embarked("Warrior", polynesia).getVisibilityRange())
    }

    @Test
    fun `ships and land units on land keep 2`() {
        assertEquals(2, game.addUnit("Frigate", civ, sea).getVisibilityRange())
        val land = game.setTileTerrain(HexCoord(2, 0), Constants.grassland)
        assertEquals(2, game.addUnit("Warrior", civ, land).getVisibilityRange())
    }

    @Test
    fun `an embarked unit sees the ring around it but not a hill two tiles away`() {
        val neighbor = game.setTileTerrain(HexCoord(1, 0), Constants.plains)
        val hill = game.setTileTerrainAndFeatures(HexCoord(2, 0), Constants.grassland, Constants.hill)
        val unit = embarked("Warrior")

        val viewable = unit.getTile().getViewableTilesList(unit.getVisibilityRange())

        assertTrue(viewable.containsAll(sea.neighbors.toList()))
        assertTrue(viewable.contains(neighbor))
        assertFalse(viewable.contains(hill))
        assertEquals(7, viewable.size)
    }
}
