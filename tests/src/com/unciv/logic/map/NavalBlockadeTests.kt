package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvCityCitizens::IsPlotBlockaded and CvCity::IsBlockaded: NAVAL_PLOT_BLOCKADE_RANGE is 2 */
@RunWith(BaseTestRunner::class)
class NavalBlockadeTests {
    private val game = TestGame()

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        // makeHexagonalMap's baseTerrain argument is applied to the old map, not the new one
        for (tile in game.tileMap.values.toList()) game.setTileTerrain(tile.position, Constants.coast)
    }

    private fun atWarCivs(): Pair<com.unciv.logic.civilization.Civilization, com.unciv.logic.civilization.Civilization> {
        val owner = game.addCiv(isPlayer = true)
        val enemy = game.addCiv()
        owner.diplomacyFunctions.makeCivilizationsMeet(enemy)
        owner.getDiplomacyManager(enemy)!!.declareWar()
        return owner to enemy
    }

    @Test
    fun `an enemy ship two tiles away blockades a worked water tile`() {
        val (owner, enemy) = atWarCivs()
        game.addCity(owner, game.getTile(0, 0))
        game.setTileTerrain(HexCoord(0, 0), Constants.desert)
        val worked = game.getTile(1, 1)
        assertTrue(worked.getOwner() == owner)
        game.addUnit("Trireme", enemy, game.getTile(3, 1))  // two tiles from (1,1)
        assertTrue(worked.aerialDistanceTo(game.getTile(3, 1)) == 2)

        assertTrue(worked.isBlockaded())
    }

    @Test
    fun `an enemy ship three tiles away does not blockade a water tile`() {
        val (owner, enemy) = atWarCivs()
        game.addCity(owner, game.getTile(0, 0))
        game.setTileTerrain(HexCoord(0, 0), Constants.desert)
        val worked = game.getTile(1, 1)
        game.addUnit("Trireme", enemy, game.getTile(4, 1))

        assertFalse(worked.isBlockaded())
    }

    @Test
    fun `a harbor city is blockaded by an enemy ship two tiles away and a city without a harbor is not`() {
        val (owner, enemy) = atWarCivs()
        val city = game.addCity(owner, game.getTile(0, 0))
        game.setTileTerrain(HexCoord(0, 0), Constants.desert)
        game.addUnit("Trireme", enemy, game.getTile(2, 0))
        city.civ.cache.updateViewableTiles()
        assertFalse("no harbor", city.isBlockaded())

        city.cityConstructions.addBuilding(game.createBuilding("Connects trade routes over water"))
        assertTrue("harbor", city.isBlockaded())
    }
}
