package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UnitActionType
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Their Finest Hour multiplies the city strength, Lightning Warfare's ignoring of zones of control, the Landsknecht of Mercenary Army
 *  and the spaceship part hurry of Spaceflight Pioneers (BNW Policies.json). */
@RunWith(GdxTestRunner::class)
class CombatPolicyTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
    }

    private fun city(civ: Civilization, x: Int = 0, y: Int = 0): City = game.addCity(civ, game.getTile(x, y))

    @Test
    fun `the city strength multiplier applies to the defense and to the ranged strike of a city`() {
        val plain = CityCombatant(city(game.addCiv()))
        val doubled = CityCombatant(city(game.addCiv("[+100]% City Strength"), 0, 2))

        // base, population and tech strength are doubled (rounding of both results costs up to 1)
        assertEquals(plain.getDefendingStrength(null) * 2f, doubled.getDefendingStrength(null).toFloat(), 1f)
        // The raw building defense is subtracted once from a ranged strike, after multiplying.
        assertEquals(plain.getAttackingStrength(null) * 2f + plain.city.getStrength(), doubled.getAttackingStrength(null).toFloat(), 1f)
    }

    @Test
    fun `Landsknecht is only purchasable after adopting Mercenary Army`() {
        val civ = game.addCiv(game.ruleset.nations["Germany"]!!)
        civ.tech.addTechnology("Civil Service")
        civ.addGold(1000)
        val city = city(civ)
        val landsknecht = game.ruleset.units["Landsknecht"]!!

        assertFalse(landsknecht.canBePurchasedWithStat(city, Stat.Gold))

        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Mercenary Army"]!!)

        assertTrue("rejections=${landsknecht.getRejectionReasons(city.cityConstructions, emptyList()).map { it.type to it.errorMessage }.toList()}, cost=${landsknecht.getBaseBuyCost(city, Stat.Gold)}", landsknecht.canBePurchasedWithStat(city, Stat.Gold))
        // purchase only: it can not be produced
        assertTrue(landsknecht.getRejectionReasons(city.cityConstructions, emptyList()).any { it.type == RejectionReasonType.Unbuildable })
    }

    @Test
    fun `a global Ignores Zone of Control with a unit filter only applies to matching units`() {
        val civ = game.addCiv("Ignores Zone of Control <for [Armored] units>")
        val tank = game.addUnit("Tank", civ, game.getTile(0, 0))
        val swordsman = game.addUnit("Swordsman", civ, game.getTile(1, 0))
        tank.updateUniques()
        swordsman.updateUniques()

        assertTrue(tank.cache.ignoresZoneOfControl)
        assertFalse(swordsman.cache.ignoresZoneOfControl)
    }

    private fun hurryAction(civ: Civilization, x: Int): com.unciv.models.UnitAction? {
        val city = city(civ, x, 0)
        city.cityConstructions.setCurrentConstruction("SS Booster")
        val engineer = game.addUnit("Great Engineer", civ, city.getCenterTile())
        return UnitActions.getUnitActions(engineer, UnitActionType.HurryBuilding).firstOrNull()
    }

    @Test
    fun `Great Engineers can hurry a spaceship part only with Spaceflight Pioneers`() {
        assertNull(hurryAction(game.addCiv(), -2)?.action)

        val action = hurryAction(game.addCiv("Units that can speed up construction of a building can also speed up construction of spaceship parts"), 2)
        assertNotNull(action?.action)
        val constructions = game.getTile(2, 0).getCity()!!.cityConstructions
        val before = constructions.getRemainingWork("SS Booster")
        action!!.action!!.invoke()
        assertTrue(constructions.getRemainingWork("SS Booster") < before)
    }

    @Test
    fun `Great Engineers can not hurry other units even with Spaceflight Pioneers`() {
        val civ = game.addCiv("Units that can speed up construction of a building can also speed up construction of spaceship parts")
        val city = city(civ)
        city.cityConstructions.setCurrentConstruction("Worker")
        val engineer = game.addUnit("Great Engineer", civ, city.getCenterTile())

        assertNull(UnitActions.getUnitActions(engineer, UnitActionType.HurryBuilding).firstOrNull()?.action)
    }
}
