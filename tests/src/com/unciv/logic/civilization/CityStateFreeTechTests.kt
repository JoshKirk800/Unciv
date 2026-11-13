package com.unciv.logic.civilization

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** [com.unciv.logic.civilization.diplomacy.CityStateFunctions.getFreeTechForCityState]: the technologies a City-State gets for free. */
@RunWith(BaseTestRunner::class)
class CityStateFreeTechTests {
    private val game = TestGame()
    private val tech = "Pottery" // needs only the starting technology Agriculture

    /** Five major civs, [knowing] of them know [tech]; returns whether the City-State has it after its free techs. */
    private fun cityStateGetsTech(knowing: Int): Boolean {
        game.makeHexagonalMap(2)
        val majors = List(5) { game.addCiv() }
        majors.forEachIndexed { i, civ -> game.addUnit("Warrior", civ, game.tileMap.values.elementAt(i)) } // a civ without cities or units is defeated
        val cityState = game.addCiv(cityStateType = "Cultured")
        Assert.assertFalse("setup", cityState.tech.isResearched(tech))
        majors.take(knowing).forEach { it.tech.addTechnology(tech) }
        cityState.cityStateFunctions.getFreeTechForCityState()
        return cityState.tech.isResearched(tech)
    }

    @Test
    fun civVNeedsFortyPercentOfTheMajorCivs() {
        game.ruleset.modOptions.constants.minorCivTechPercent = 40
        // 5 majors x 40% = 2 are enough
        Assert.assertTrue(cityStateGetsTech(2))
    }

    @Test
    fun civVOneMajorIsNotEnoughForFiveMajors() {
        game.ruleset.modOptions.constants.minorCivTechPercent = 40
        Assert.assertFalse(cityStateGetsTech(1))
    }

    @Test
    fun defaultNeedsMoreThanHalf() {
        game.ruleset.modOptions.constants.minorCivTechPercent = 0
        Assert.assertFalse(cityStateGetsTech(2))
    }
}
