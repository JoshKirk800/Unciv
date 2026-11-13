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

/** CvUnit::currInterceptionProbability: the chance of an air unit to intercept is its maximum times its health / max health
 *  (the Gods & Kings and Brave New World rulesets set airInterceptionChanceScalesWithHealth); other units keep the maximum. */
@RunWith(BaseTestRunner::class)
class AirInterceptionChanceTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        game.addCity(us, game.getTile(0, 0)) // air units need a city to stand in
        game.addCity(them, game.getTile(3, 0))
    }

    private fun interceptor(chance: Int, health: Int, tile: Int = 3): MapUnit {
        val base = game.createBaseUnit("Fighter", "[$chance]% chance to intercept air attacks")
        base.strength = 45
        base.rangedStrength = 45
        base.range = 8
        base.interceptRange = 8
        val unit = game.addUnit(base.name, them, game.getTile(tile, 0))
        unit.health = health
        return unit
    }

    @Test
    fun `an air unit intercepts with its chance times its health, rounded down`() {
        assertEquals(100, interceptor(100, 100).currentInterceptChance())
        assertEquals(50, interceptor(100, 50).currentInterceptChance())
        assertEquals(20, interceptor(40, 50).currentInterceptChance())
        assertEquals(1, interceptor(100, 1).currentInterceptChance())
        assertEquals(0, interceptor(50, 1).currentInterceptChance())
    }

    @Test
    fun `a land unit with an interception chance is not scaled by its health`() {
        val base = game.createBaseUnit("Archery", "[50]% chance to intercept air attacks")
        val unit = game.addUnit(base.name, them, game.getTile(2, 0))
        unit.health = 40
        assertEquals(50, unit.currentInterceptChance())
    }

    @Test
    fun `without the ruleset constant the chance stays whatever the health`() {
        game.ruleset.modOptions.constants.airInterceptionChanceScalesWithHealth = false
        assertEquals(100, interceptor(100, 10).currentInterceptChance())
    }

    @Test
    fun `an interceptor with no chance left does not intercept`() {
        val bomber = game.addUnit("Great War Bomber", us, game.getTile(0, 0))
        val target = game.addDefaultMeleeUnitWithUniques(them, game.getTile(2, 0))
        // 99% of 1 health is 0: it is not picked, whatever the roll
        val weak = interceptor(99, 1)

        Battle.attack(MapUnitCombatant(bomber), MapUnitCombatant(target))

        assertTrue("it could intercept by its maximum", weak.canIntercept(target.currentTile))
        assertEquals(0, weak.currentInterceptChance())
        assertEquals("it did not take a shot (an interceptor that is picked does, even when it misses)", 0, weak.attacksThisTurn)
    }
}
