package com.unciv.logic.automation.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the barbarians know (CvTeam::DoBarbarianTech): the techs that 75% of the living majors know, city-states not counted */
@RunWith(BaseTestRunner::class)
class BarbarianTechTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val tech = "Pottery"

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.addBarbarianCiv().setTransients()
        game.gameInfo.barbarians.setTransients(game.gameInfo)
    }

    private fun barbariansKnow() = game.gameInfo.getBarbarianCivilization().tech.isResearched(tech)

    private fun majorsKnowing(known: Int, of: Int) {
        repeat(of) { index ->
            val civ = game.addCiv()
            game.addUnit("Warrior", civ, game.tileMap.values.elementAt(index)) // a civilization without units or cities is defeated
            if (index < known) civ.tech.addTechnology(tech, showNotification = false)
        }
    }

    @Test
    fun `the barbarians learn a tech that three of four majors know`() {
        majorsKnowing(known = 3, of = 4)
        game.gameInfo.barbarians.updateBarbarianTech()
        assertTrue(barbariansKnow())
    }

    @Test
    fun `the barbarians do not learn a tech that only half of the majors know`() {
        majorsKnowing(known = 2, of = 4)
        game.gameInfo.barbarians.updateBarbarianTech()
        assertFalse(barbariansKnow())
    }

    @Test
    fun `the techs of city-states do not count`() {
        majorsKnowing(known = 1, of = 4)
        repeat(3) {
            val cityState = game.addCiv(cityStateType = "Cultured")
            game.addUnit("Warrior", cityState, game.tileMap.values.elementAt(10 + it))
            cityState.tech.addTechnology(tech, showNotification = false)
        }
        game.gameInfo.barbarians.updateBarbarianTech()
        assertFalse(barbariansKnow())
    }

    @Test
    fun `a tech once learned is not lost`() {
        majorsKnowing(known = 3, of = 4)
        game.gameInfo.barbarians.updateBarbarianTech()
        game.gameInfo.civilizations.filter { it.isMajorCiv }.forEach { it.tech.techsResearched.remove(tech) }
        game.gameInfo.barbarians.updateBarbarianTech()
        assertTrue(barbariansKnow())
    }

    @Test
    fun `the barbarians' era follows their techs`() {
        majorsKnowing(known = 4, of = 4)
        game.gameInfo.barbarians.updateBarbarianTech()
        assertEquals(game.ruleset.technologies.getValue(tech).era(), game.gameInfo.getBarbarianCivilization().getEra().name)
    }
}
