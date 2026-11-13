package com.unciv.logic.map

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CvUnit::getMaxHurryProduction, getHurryProduction, canHurry: a Great Engineer adds (300 + 30 x population) x the speed's UnitHurryPercent / 100,
 * at most all that is still missing, and cannot hurry production that is completed next turn anyway. Needs the BNW ruleset (unitHurryLikeCivV).
 */
@RunWith(BaseTestRunner::class)
class GreatEngineerHurryTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        civ = game.addCiv("[+5 Production] [in all cities]")
    }

    private fun cityBuilding(cost: Int, population: Int = 3): Pair<City, String> {
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = population)
        val building = game.createBuilding()
        building.cost = cost
        city.cityConstructions.setCurrentConstruction(building.name)
        city.cityStats.update()
        return city to building.name
    }

    private fun hurryAction(city: City): com.unciv.models.UnitAction? {
        val engineer: MapUnit = game.addUnit("Great Engineer", civ, city.getCenterTile())
        return UnitActions.getUnitActions(engineer, UnitActionType.HurryBuilding).firstOrNull()
    }

    @Test
    fun `adds 300 plus 30 per citizen`() {
        val (city, building) = cityBuilding(1000, population = 3)
        hurryAction(city)!!.action!!.invoke()
        assertEquals(1000 - (300 + 30 * 3), city.cityConstructions.getRemainingWork(building))
    }

    @Test
    fun `can pay for everything that is left`() {
        val (city, building) = cityBuilding(200)
        hurryAction(city)!!.action!!.invoke()
        // all 200 are added: the building is done, not 1 short of it
        assertTrue(city.cityConstructions.isBuilt(building))
    }

    @Test
    fun `is not offered when the production is done next turn anyway`() {
        val (city, building) = cityBuilding(1)
        val perTurn = city.cityConstructions.productionForConstruction(building)
        assertTrue("the test needs a city that makes at least 2 production, makes $perTurn", perTurn >= 2)
        game.ruleset.buildings[building]!!.cost = perTurn
        assertEquals(1, city.cityConstructions.turnsToConstruction(building))
        assertNull(hurryAction(city)?.action)
    }

    @Test
    fun `without the constant the hurry stops one short`() {
        game.ruleset.modOptions.constants.unitHurryLikeCivV = false
        val (city, building) = cityBuilding(200)
        hurryAction(city)!!.action!!.invoke()
        assertEquals(1, city.cityConstructions.getRemainingWork(building))
    }
}
