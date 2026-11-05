package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A melee attack on a unit applies both damages in full; if that would kill both, the unit with less total damage survives with 1 HP
 *  (CvUnitCombat::GenerateMeleeCombatInfo). */
@RunWith(BaseTestRunner::class)
class MeleeBothDieTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
    }

    private fun unit(civ: Civilization, x: Int, strength: Int, health: Int): MapUnit {
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = strength
        return game.addUnit(base.name, civ, game.getTile(x, 0)).also { it.health = health }
    }

    private fun fight(attacker: MapUnit, defender: MapUnit) =
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))

    @Test
    fun `the stronger attacker survives with 1 HP when both would die`() {
        val attacker = unit(us, 0, strength = 30, health = 6)
        val defender = unit(them, 1, strength = 10, health = 6)
        val combatants = MapUnitCombatant(attacker) to MapUnitCombatant(defender)
        assertTrue("both would die by the raw damage",
            BattleDamage.calculateDamageToDefender(combatants.first, combatants.second) >= 6
                && BattleDamage.calculateDamageToAttacker(combatants.first, combatants.second) >= 6)

        fight(attacker, defender)

        assertEquals(1, attacker.health)
        assertTrue(defender.isDestroyed)
    }

    @Test
    fun `the stronger defender survives with 1 HP when both would die`() {
        val attacker = unit(us, 0, strength = 10, health = 6)
        val defender = unit(them, 1, strength = 30, health = 6)

        fight(attacker, defender)

        assertTrue(attacker.isDestroyed)
        assertEquals(1, defender.health)
        assertFalse(defender.isDestroyed)
    }

    @Test
    fun `a unit that dies alone takes the full damage of the other one`() {
        val attacker = unit(us, 0, strength = 30, health = 100)
        val defender = unit(them, 1, strength = 10, health = 6)
        val expected = BattleDamage.calculateDamageToAttacker(MapUnitCombatant(attacker), MapUnitCombatant(defender))

        fight(attacker, defender)

        assertTrue(defender.isDestroyed)
        assertEquals("the attacker takes everything the defender could deal, not a part", 100 - expected, attacker.health)
    }

    @Test
    fun `on equal total damage the defender survives`() {
        val attacker = MapUnitCombatant(unit(us, 0, strength = 10, health = 6))
        val defender = MapUnitCombatant(unit(them, 1, strength = 10, health = 6))

        val (toDefender, toAttacker) = BattleDamage.resolveMeleeDeaths(attacker, defender, 40, 40)

        assertEquals(5, toDefender)
        assertEquals(40, toAttacker)
    }

    @Test
    fun `an attacker that would die is not offered the attack unless it survives by the rule`() {
        val strong = MapUnitCombatant(unit(us, 0, strength = 30, health = 6))
        val weakTarget = MapUnitCombatant(unit(them, 1, strength = 10, health = 6))
        val weak = MapUnitCombatant(unit(us, -1, strength = 10, health = 6))
        val strongTarget = MapUnitCombatant(unit(them, 2, strength = 30, health = 6))

        assertTrue(BattleDamage.attackerSurvives(strong, weakTarget))
        assertFalse(BattleDamage.attackerSurvives(weak, strongTarget))
    }
}
