package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The era a game starts in scales costs for the whole game (CvEraInfo in the original). Uses the percentages of the G&K ruleset:
 *  Ancient is 100% everywhere, Renaissance 33 research, 50 train, 55 construct and create, 90 great people, growth, build, 75 maintenance;
 *  Industrial 60 maintenance, Modern 70 build. */
@RunWith(BaseTestRunner::class)
class StartingEraPercentTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
        startIn("Ancient era")
    }

    private fun startIn(era: String) {
        game.gameInfo.gameParameters.startingEra = era
    }

    /** [value] in the Ancient era, then in [era] */
    private fun <T> both(era: String, value: () -> T): Pair<T, T> {
        startIn("Ancient era")
        val ancient = value()
        startIn(era)
        return ancient to value()
    }

    private fun assertScaled(percent: Int, ancient: Int, scaled: Int) =
        assertEquals("$ancient scaled to $percent%", ancient * percent / 100f, scaled.toFloat(), 1.01f)

    @Test
    fun `research percent scales the cost of a tech`() {
        val (ancient, scaled) = both("Renaissance era") { civ.tech.costOfTech("Education") }
        assertTrue(ancient > 20)
        assertScaled(33, ancient, scaled)
    }

    @Test
    fun `train percent scales units, construct percent buildings and wonders`() {
        val warrior = game.ruleset.units["Warrior"]!!
        val monument = game.ruleset.buildings["Monument"]!!
        val wonder = game.ruleset.buildings["Great Wall"]!!
        val (unitBefore, unitAfter) = both("Renaissance era") { warrior.getProductionCost(civ, city) }
        val (buildingBefore, buildingAfter) = both("Renaissance era") { monument.getProductionCost(civ, city) }
        val (wonderBefore, wonderAfter) = both("Renaissance era") { wonder.getProductionCost(civ, city) }
        assertScaled(50, unitBefore, unitAfter)
        assertScaled(55, buildingBefore, buildingAfter)
        assertScaled(55, wonderBefore, wonderAfter)
    }

    @Test
    fun `create percent, not train percent, scales spaceship parts`() {
        val part = game.createBaseUnit(game.createUnitType().name, "Can be added to [Spaceship] in the Capital")
        part.cost = 1000
        val (before, after) = both("Renaissance era") { part.getProductionCost(civ, city) }
        assertEquals(1000, before)
        assertEquals("55% (create), not 50% (train)", 550, after)
    }

    @Test
    fun `great people percent scales the points for a great person and for a great general`() {
        val (before, after) = both("Renaissance era") { civ.greatPeople.getPointsRequiredForGreatPerson("Great Scientist") }
        assertScaled(90, before, after)

        civ.greatPeople.greatGeneralPointsCounter["Great General"] = 190
        startIn("Ancient era")
        assertNull("200 needed", civ.greatPeople.getNewGreatGeneral())
        startIn("Renaissance era")
        assertEquals("180 needed", "Great General", civ.greatPeople.getNewGreatGeneral())
    }

    @Test
    fun `growth percent scales the food a city needs`() {
        city.population.setPopulation(3)
        val (before, after) = both("Renaissance era") { city.population.getFoodToNextPopulation() }
        assertScaled(90, before, after)
    }

    @Test
    fun `build percent scales the time workers need`() {
        val improvement = game.createTileImprovement()
        improvement.turnsToBuild = 100
        val worker = game.addUnit("Worker", civ, game.getTile(1, 0))
        val (before, after) = both("Modern era") { improvement.getTurnsToBuild(civ, worker) }
        assertEquals(100, before)
        assertEquals(70, after)
    }

    @Test
    fun `building maintenance percent scales the gold the buildings cost`() {
        val building = game.createBuilding()
        building.maintenance = 100
        city.cityConstructions.addBuilding(building)
        fun maintenance(): Float { city.cityStats.update(); return city.cityStats.finalStatList["Maintenance"]!!.gold }

        val (before, after) = both("Industrial era") { maintenance() }
        assertEquals(-100f, before, 0.01f)
        assertEquals(-60f, after, 0.01f)
    }
}
