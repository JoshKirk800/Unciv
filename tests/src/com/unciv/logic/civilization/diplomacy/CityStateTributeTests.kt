package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.battle.CityCombatant
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/** The military parts of the tribute willingness against CvMinorCivAI::CalculateBullyMetric (BNW: 75 for the rank, 125 for the local power) */
@RunWith(GdxTestRunner::class)
class CityStateTributeTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
    }

    @Test
    fun `the local military score compares the original's unit power with the capital's city power`() {
        val bully = game.addCiv()
        game.addCity(bully, game.tileMap[0, -6], replacePalace = true)
        val cs = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val capital = game.addCity(cs, game.tileMap[0, 6], replacePalace = true)
        bully.diplomacyFunctions.makeCivilizationsMeet(cs)
        val cityPower = CityCombatant(capital).getDefendingStrength().toDouble().pow(1.5).toInt()

        // a unit strength whose power (CvUnit::GetPower) is between one and one and a half times the city's: the "1.0" step, 2/5 of 125
        val target = game.tileMap[0, 3]
        var found = false
        for (strength in 5..60) {
            val base = game.createBaseUnit("Sword")
            base.movement = 2
            base.strength = strength
            val unit = game.addUnit(base.name, bully, target)
            val power = unit.getAiPower()
            if (power >= cityPower && power * 2 < cityPower * 3) { found = true; break }
            unit.destroy()
        }
        assertTrue("a strength with a power in [city power, 1.5 x city power) exists", found)

        val modifiers = cs.cityStateFunctions.getTributeModifiers(bully, requireWholeList = true)
        assertEquals(125 * 2 / 5, modifiers["Military near City-State"])
        // the only major: first of one
        assertEquals(75, modifiers["Military Rank"])
    }
}
