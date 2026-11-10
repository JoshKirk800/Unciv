package com.unciv.logic.automation.strategy

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/** The unit type power the military AI compares (CvUnitEntry::GetPower) against the DLL formula, for units with no promotion modifiers */
@RunWith(GdxTestRunner::class)
class UnitPowerTests {
    private val ruleset = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).ruleset

    /** strength^1.5, or ranged strength^1.45 (x3/4 at sea) when higher, each cut to a whole number; the mobility term is min(1, moves)^0.3 = 1 */
    private fun expectedPower(strength: Int, ranged: Int, sea: Boolean): Int {
        val melee = strength.toDouble().pow(1.5).toInt()
        var rangedPower = ranged.toDouble().pow(1.45).toInt()
        if (sea) rangedPower = rangedPower * 3 / 4
        return if (rangedPower > melee) rangedPower else melee
    }

    @Test
    fun powerFollowsTheOriginalFormulaAndIgnoresMovement() {
        val plain = ruleset.units.values.filter {
            (it.strength > 0 || it.rangedStrength > 0) && it.movement > 0 &&
                it.rulesetUniqueObjects.isEmpty() && it.promotions.isEmpty()
        }
        assertTrue("units without modifiers to check: ${plain.map { it.name }}", plain.size >= 5)
        assertTrue("a unit that moves more than once is among them", plain.any { it.movement > 1 })
        for (unit in plain)
            assertEquals(unit.name, expectedPower(unit.strength, unit.rangedStrength, unit.isWaterUnit), unit.getAiPower())
    }
}
