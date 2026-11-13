package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In a bombing mission the damage of each side is proportional to its health (CvUnit::GetAirCombatDamage, GetAirStrikeDefenseDamage),
 *  a city's too; for every other attack a unit loses 1% of its damage per 3 HP lost and a city is not weakened at all. */
@RunWith(BaseTestRunner::class)
class AirStrikeDamageTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var bomber: MapUnit

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        game.addCity(us, game.getTile(0, 0))
        bomber = game.addUnit("Great War Bomber", us, game.getTile(0, 0))
    }

    private fun damageToDefender(attacker: MapUnit, health: Int, defender: ICombatant): Int {
        attacker.health = health
        // a fixed roll, so that only the health changes between the calls
        return BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), defender, randomnessFactor = 0.5f)
    }

    @Test
    fun `a wounded bomber deals damage in proportion to its health`() {
        val target = MapUnitCombatant(game.addDefaultMeleeUnitWithUniques(them, game.getTile(2, 0)))

        val full = damageToDefender(bomber, 100, target)
        val half = damageToDefender(bomber, 50, target)

        assertTrue("full $full, half $half", half in (full * 45 / 100)..(full * 55 / 100) + 1)
    }

    @Test
    fun `a wounded land ranged unit still loses only a third of its damage per half of its health`() {
        val archer = game.addDefaultRangedUnitWithUniques(us, game.getTile(1, 1))
        val target = MapUnitCombatant(game.addDefaultMeleeUnitWithUniques(them, game.getTile(2, 0)))

        val full = damageToDefender(archer, 100, target)
        val half = damageToDefender(archer, 50, target)

        assertTrue("full $full, half $half", half >= full * 80 / 100)
    }

    @Test
    fun `a city hits a bomber in proportion to its health`() {
        val city = game.addCity(them, game.getTile(3, 0))
        val cityCombatant = CityCombatant(city)
        val attacker = MapUnitCombatant(bomber)

        city.health = cityCombatant.getMaxHealth()
        val full = BattleDamage.calculateDamageToAttacker(attacker, cityCombatant, randomnessFactor = 0.5f)
        city.health = cityCombatant.getMaxHealth() / 2
        val half = BattleDamage.calculateDamageToAttacker(attacker, cityCombatant, randomnessFactor = 0.5f)

        assertTrue("full $full, half $half", half in (full * 45 / 100)..(full * 55 / 100) + 1)
    }

    @Test
    fun `a city does not weaken against a melee attack`() {
        val city = game.addCity(them, game.getTile(3, 0))
        val cityCombatant = CityCombatant(city)
        val attacker = MapUnitCombatant(game.addDefaultMeleeUnitWithUniques(us, game.getTile(2, 0)))

        city.health = cityCombatant.getMaxHealth()
        val full = BattleDamage.calculateDamageToAttacker(attacker, cityCombatant, randomnessFactor = 0.5f)
        city.health = 10
        val low = BattleDamage.calculateDamageToAttacker(attacker, cityCombatant, randomnessFactor = 0.5f)

        assertEquals(full, low)
    }

    /** CvUnitCombat::GenerateAirSweepCombatInfo: against an air unit 5 XP for the attacker and 5 for the defender; a bombing mission 4 and 2 */
    @Test
    fun `an air sweep against an air unit gives 5 and 5 experience, a bombing mission 4 and 2`() {
        game.addCity(them, game.getTile(3, 0))
        val fighter = game.addUnit("Fighter", us, game.getTile(0, 0))
        val enemyFighter = game.addUnit("Fighter", them, game.getTile(3, 0))
        Battle.attack(MapUnitCombatant(fighter), MapUnitCombatant(enemyFighter))
        assertEquals("attacker", 5, fighter.promotions.XP)
        assertEquals("defender", 5, enemyFighter.promotions.XP)

        val target = game.addDefaultMeleeUnitWithUniques(them, game.getTile(2, 0))
        val xpBefore = bomber.promotions.XP
        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))
        assertEquals("bomber", 4, bomber.promotions.XP - xpBefore)
        assertEquals("target", 2, target.promotions.XP)
    }
}
