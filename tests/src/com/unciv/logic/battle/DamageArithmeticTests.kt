package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The original works in whole numbers: damage is truncated, at least 1 is dealt, and a unit keeps at least 10% of its strength
 *  (CvUnit::getCombatDamage, GetMaxAttackStrength). */
@RunWith(BaseTestRunner::class)
class DamageArithmeticTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        us = game.addCiv()
        them = game.addCiv()
    }

    private fun unit(civ: Civilization, x: Int, strength: Int, vararg uniques: String): MapUnit {
        val base = game.createBaseUnit("Sword", *uniques)
        base.movement = 2
        base.strength = strength
        return game.addUnit(base.name, civ, game.getTile(x, 0))
    }

    @Test
    fun `damage is truncated to whole hit points`() {
        val attacker = MapUnitCombatant(unit(us, 0, 20))
        val defender = MapUnitCombatant(unit(them, 1, 20))
        val tile = defender.getTile()
        assertEquals("equal strengths needed for this test",
            BattleDamage.getAttackingStrength(attacker, defender, tile), BattleDamage.getDefendingStrength(attacker, defender, tile), 0.001f)

        // 24 + 12 * 0.9 = 34.8 at equal strength and full health
        assertEquals(34, BattleDamage.calculateDamageToDefender(attacker, defender, randomnessFactor = 0.9f))
    }

    @Test
    fun `a hit always deals at least 1 HP`() {
        val weak = MapUnitCombatant(unit(us, 0, 1))
        val strong = MapUnitCombatant(unit(them, 1, 100))

        assertEquals(1, BattleDamage.calculateDamageToDefender(weak, strong, randomnessFactor = 0f))
    }

    @Test
    fun `strength modifiers cannot take a unit below 10 percent`() {
        val crippled = MapUnitCombatant(unit(us, 0, 30, "[-200]% Strength"))
        val defender = MapUnitCombatant(unit(them, 1, 30))

        assertEquals(3f, BattleDamage.getAttackingStrength(crippled, defender, defender.getTile()), 0.001f)
    }
}
