package com.unciv.logic.civilization.diplomacy

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CvGame::GetCompetitiveSpawnUnitType: a city-state's gifted military unit comes from the strongest UNIT_SPAWN_NUM_CHOICES (4) it can build */
@RunWith(BaseTestRunner::class)
class CityStateUnitGiftTests {
    private val game = TestGame()

    @Test
    fun `a gifted unit is one of the four most powerful the receiver can build`() {
        game.makeHexagonalMap(32)
        val cityState = game.addCiv(cityStateType = "Militaristic")
        game.addCity(cityState, game.getTile(-32, 0), true)

        var checked = 0
        // The roll is seeded from the two civilizations, so every receiver is a new draw
        for (i in 1..60) {
            val patron = game.addCiv(isPlayer = true)
            patron.diplomacyFunctions.makeCivilizationsMeet(cityState)
            patron.tech.techsResearched.addAll(game.ruleset.technologies.keys)
            val patronCity = game.addCity(patron, game.getTile(-30 + i, 0))
            val pool = patronCity.cityConstructions.getConstructableUnits()
                .filter { !it.isCivilian() && it.isLandUnit && it.uniqueTo == null }.map { it.name to it.getAiPower() }.toList()
            val fourthPower = pool.map { it.second }.sortedDescending()[3]
            assertTrue("the pool is larger than the four choices: $pool", pool.size > 4)

            cityState.cityStateFunctions.giveMilitaryUnitToPatron(patron)
            val gifted = patron.units.getCivUnits().singleOrNull() ?: continue
            checked++
            val power = gifted.baseUnit.getAiPower()
            assertTrue("receiver $i: ${gifted.name} has power $power, the fourth best is $fourthPower", power >= fourthPower)
        }
        assertTrue("only $checked gifts placed", checked >= 30)
    }
}
