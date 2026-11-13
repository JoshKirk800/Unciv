package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CvUnit::isRangedSupportFire (promotion RangedSupportFire, the Zulu Impi's Spear Throw): CvUnitCombat::Attack makes the extra ranged attack first
 * and does not fight a defender it kills; CvUnit::GetMaxRangedCombatStrength gives it all unit modifiers; CvUnit::fortifyModifier gives the
 * unit no fortification bonus. Needs the BNW ruleset (rangedSupportFireUnitsGetNoFortification).
 */
@RunWith(BaseTestRunner::class)
class RangedSupportFireTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var zulu: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        zulu = game.addCiv()
        enemy = game.addCiv()
        zulu.diplomacyFunctions.makeCivilizationsMeet(enemy)
        zulu.getDiplomacyManager(enemy)!!.declareWar()
    }

    private fun defenderOfType(unitType: String): MapUnitCombatant {
        val base = game.createBaseUnit(unitType)
        base.strength = 20
        base.movement = 2
        return MapUnitCombatant(game.addUnit(base.name, enemy, game.getTile(1, 0)))
    }

    @Test
    fun `the Spear Throw has the unit's bonus against mounted units`() {
        val impi = game.addUnit("Impi", zulu, game.getTile(0, 0))
        val mounted = defenderOfType("Mounted")
        val maxAgainstMounted = BattleDamage.getExtraRangedAttackBonusDamage(MapUnitCombatant(impi), mounted, mounted.getTile()).first
        mounted.unit.destroy()
        val sword = defenderOfType("Sword")
        val maxAgainstSword = BattleDamage.getExtraRangedAttackBonusDamage(MapUnitCombatant(impi), sword, sword.getTile()).first
        assertTrue("$maxAgainstMounted against mounted, $maxAgainstSword against sword", maxAgainstMounted > maxAgainstSword)
    }

    @Test
    fun `a defender killed by the Spear Throw is not fought in melee`() {
        val impi = game.addUnit("Impi", zulu, game.getTile(0, 0))
        val defender = defenderOfType("Sword")
        defender.unit.health = 1
        Battle.attack(MapUnitCombatant(impi), defender)
        assertTrue(defender.isDefeated())
        assertEquals("no melee damage from a dead defender", 100, impi.health)
        assertEquals(game.getTile(1, 0), impi.getTile())
    }

    @Test
    fun `a fortified Impi gets no fortification bonus, another swordsman does`() {
        val impi = game.addUnit("Impi", enemy, game.getTile(1, 0))
        val swordsman = game.addUnit("Swordsman", enemy, game.getTile(1, 1))
        val attacker = MapUnitCombatant(game.addUnit("Swordsman", zulu, game.getTile(0, 0)))
        for (unit in listOf(impi, swordsman)) {
            unit.fortify()
            unit.turnsFortified = 2
        }
        val tile = attacker.getTile()
        assertFalse("Fortification" in BattleDamage.getDefenceModifiers(attacker, MapUnitCombatant(impi), tile))
        assertTrue("Fortification" in BattleDamage.getDefenceModifiers(attacker, MapUnitCombatant(swordsman), tile))
    }

    @Test
    fun `without the constant a fortified Impi gets the bonus`() {
        game.ruleset.modOptions.constants.rangedSupportFireUnitsGetNoFortification = false
        val impi = game.addUnit("Impi", enemy, game.getTile(1, 0))
        impi.fortify()
        impi.turnsFortified = 2
        val attacker = MapUnitCombatant(game.addUnit("Swordsman", zulu, game.getTile(0, 0)))
        assertTrue("Fortification" in BattleDamage.getDefenceModifiers(attacker, MapUnitCombatant(impi), attacker.getTile()))
    }
}
