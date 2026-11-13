package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What a damaged city heals in [CityTurnManager.endTurn] (CvCity::doTurn). */
@RunWith(BaseTestRunner::class)
class CityHealTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var other: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(HexCoord.Zero), replacePalace = true)
        other = game.addCity(civ, game.getTile(4, 0))
    }

    private fun healed(city: City): Int {
        city.health = 100
        CityTurnManager(city).endTurn()
        return city.health - 100
    }

    @Test
    fun civVHealsOneMoreInTheCapitalOfAMajorCiv() {
        game.ruleset.modOptions.constants.cityHealLikeCivV = true
        assertEquals(21, healed(capital))
        assertEquals(20, healed(other))
    }

    @Test
    fun civVHealsOnePerFiveHundredDefenseOfBuildings() {
        game.ruleset.modOptions.constants.cityHealLikeCivV = true
        other.cityConstructions.addBuilding("Walls")
        assertEquals(21, healed(other))
        other.cityConstructions.addBuilding("Castle")
        assertEquals(22, healed(other)) // 1200 / 500
    }

    @Test
    fun defaultHealsTwentyEverywhere() {
        game.ruleset.modOptions.constants.cityHealLikeCivV = false
        other.cityConstructions.addBuilding("Walls")
        assertEquals(20, healed(capital))
        assertEquals(20, healed(other))
    }
}
