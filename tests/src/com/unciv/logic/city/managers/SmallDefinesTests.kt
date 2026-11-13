package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Resource demand countdowns (CvCity::DoSeedResourceDemandedCountdown, DoTestResourceDemanded) and the turns between City-State elections
 *  (CvGame::GetTurnsBetweenMinorCivElections) on the Marathon speed (modifier 3). */
@RunWith(BaseTestRunner::class)
class SmallDefinesTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var capital: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), replacePalace = true)
        game.setSpeed("Marathon")
        assertEquals(3f, game.gameInfo.speed.modifier, 0f)
    }

    @Test
    fun civVSeedsTheCapitalDemandCountdownWithoutGameSpeed() {
        game.ruleset.modOptions.constants.resourceDemandLikeCivV = true
        CityTurnManager(capital).startTurn()
        // 15 + 25 + random(10)
        assertTrue(capital.getFlag(CityFlags.ResourceDemand) in 40..49)
    }

    @Test
    fun defaultScalesTheCountdownWithGameSpeed() {
        game.ruleset.modOptions.constants.resourceDemandLikeCivV = false
        CityTurnManager(capital).startTurn()
        // (15 + 10 + random(10)) x 3
        assertTrue(capital.getFlag(CityFlags.ResourceDemand) in 75..102)
    }

    private fun wltkdDaysLeftAfterStart(): Int {
        civ.tech.addTechnology("Mining")
        capital.getCenterTile().setTileResource("Salt")
        capital.getCenterTile().resourceAmount = 1
        civ.cache.updateCivResources()
        capital.demandedResource = "Salt"
        CityTurnManager(capital).startTurn()
        return capital.getFlag(CityFlags.WeLoveTheKing)
    }

    @Test
    fun civVWltkdLastsTwentyTurnsOnAnySpeed() {
        game.ruleset.modOptions.constants.resourceDemandLikeCivV = true
        assertEquals(20, wltkdDaysLeftAfterStart())
    }

    @Test
    fun defaultWltkdIsScaledByGameSpeed() {
        game.ruleset.modOptions.constants.resourceDemandLikeCivV = false
        assertEquals(60, wltkdDaysLeftAfterStart())
    }

    @Test
    fun civVElectionIntervalIsScaledByGameSpeed() {
        game.ruleset.modOptions.constants.cityStateElectionsScaleWithGameSpeed = true
        assertEquals(45, civ.cityStateFunctions.getTurnsBetweenElections())
        game.setSpeed("Quick")
        assertEquals(10, civ.cityStateFunctions.getTurnsBetweenElections())
    }

    @Test
    fun defaultElectionIntervalIsFifteen() {
        game.ruleset.modOptions.constants.cityStateElectionsScaleWithGameSpeed = false
        assertEquals(15, civ.cityStateFunctions.getTurnsBetweenElections())
    }
}
