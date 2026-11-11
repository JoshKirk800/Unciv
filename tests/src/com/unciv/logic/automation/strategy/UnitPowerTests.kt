package com.unciv.logic.automation.strategy

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/** The unit type power the military AI compares (CvUnitEntry::GetPower) against the DLL formula, for units whose XML free promotions change nothing */
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
        // none of these has a free promotion with a power modifier in the XML (checked against the table the generator wrote)
        val plain = listOf("Warrior", "Swordsman", "Longswordsman", "Musketman", "Knight", "Horseman", "Archer", "Tank", "Battleship")
            .map { ruleset.units[it]!! }
        assertTrue("a unit that moves more than once is among them", plain.any { it.movement > 1 })
        assertTrue("a ranged and a naval unit are among them", plain.any { it.rangedStrength > 0 } && plain.any { it.isWaterUnit })
        for (unit in plain)
            assertEquals(unit.name, expectedPower(unit.strength, unit.rangedStrength, unit.isWaterUnit), unit.getAiPower())
    }

    /** Hand-derived from the XML, every step cut to a whole number (iPower += iPower * percent / divisor / 100) */
    @Test
    fun powerIncludesTheModifiersOfTheUnitsFreePromotions() {
        // 11^1.5 = 36; Anti-Mounted I: +50% against mounted, divisor 4 -> +4
        assertEquals(40, ruleset.units["Spearman"]!!.getAiPower())
        // ranged 8^1.45 = 20 beats melee 7^1.5 = 18; City Siege 200% / 2 -> +20; Must Set Up -1/5 -> -8; Only Defensive does not apply to a ranged unit
        assertEquals(32, ruleset.units["Catapult"]!!.getAiPower())
        // 45^1.45 = 249; Anti-Air II: +150% vs bombers and +150% vs helicopters, divisor 4 -> +93, then +128
        assertEquals(470, ruleset.units["Fighter"]!!.getAiPower())
        // 65^1.5 = 524; Paradrop +1/4 -> +131
        assertEquals(655, ruleset.units["Paratrooper"]!!.getAiPower())
        // 40^1.5 = 252; Only Defensive on a unit with no ranged strength -1/4 -> -63
        assertEquals(189, ruleset.units["Carrier"]!!.getAiPower())
    }
}
