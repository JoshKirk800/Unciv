package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CvCity::GetLocalHappiness: the happiness of a city's buildings, policies and beliefs per building, garrison and river is limited to its
 * population, but not that of UnmoddedHappiness buildings and not the civilization-wide happiness. Needs the BNW ruleset (localHappinessCappedByPopulation).
 */
@RunWith(BaseTestRunner::class)
class LocalHappinessTests {
    private val limitKey = "Population limit on local happiness"
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        civ = game.addCiv()
    }

    private fun city(population: Int, vararg buildings: String): City {
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = population)
        for (building in buildings) city.cityConstructions.addBuilding(building)
        city.cityStats.update()
        return city
    }

    @Test
    fun `building happiness above the population is cut off`() {
        val city = city(2, "Colosseum", "Zoo", "Circus")
        assertEquals(6f, city.cityStats.happinessList["Buildings"]!!, 0.001f)
        assertEquals(-4f, city.cityStats.happinessList[limitKey]!!, 0.001f)
        assertEquals(2f, city.cityStats.localHappiness, 0.001f)
    }

    @Test
    fun `building happiness below the population is not touched`() {
        val city = city(3, "Colosseum")
        assertNull(city.cityStats.happinessList[limitKey])
        assertEquals(2f, city.cityStats.localHappiness, 0.001f)
    }

    @Test
    fun `unmodded happiness of Circus Maximus is not limited`() {
        val city = city(1, "Circus Maximus")
        assertEquals(5f, city.cityStats.happinessList["Buildings"]!!, 0.001f)
        assertNull(city.cityStats.happinessList[limitKey])
    }

    @Test
    fun `a policy's happiness per building counts in the same limit`() {
        val city = city(1, "Colosseum", "Castle")
        assertEquals(-1f, city.cityStats.happinessList[limitKey]!!, 0.001f)  // the Colosseum's 2 alone
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Fortified Borders"]!!)
        city.cityStats.update()
        assertEquals(-2f, city.cityStats.happinessList[limitKey]!!, 0.001f)  // plus 1 for the Castle
    }

    @Test
    fun `the garrison policy happiness is limited with the buildings`() {
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Military Caste"]!!)
        val city = city(1)
        game.addUnit("Warrior", civ, city.getCenterTile())
        city.cityStats.update()
        assertEquals(1f, city.cityStats.localHappiness, 0.001f)
        city.cityConstructions.addBuilding("Colosseum")
        city.cityStats.update()
        assertEquals(1f, city.cityStats.localHappiness, 0.001f)
        assertEquals(-2f, city.cityStats.happinessList[limitKey]!!, 0.001f)
    }

    @Test
    fun `India's limit is two thirds of the population, rounded`() {
        civ = game.addCiv("[+100]% unhappiness from the number of cities")
        val city = city(4, "Colosseum", "Zoo", "Circus")
        assertEquals(3f, city.cityStats.localHappiness, 0.001f)
        assertEquals(-3f, city.cityStats.happinessList[limitKey]!!, 0.001f)
    }

    @Test
    fun `without the constant nothing is limited`() {
        game.ruleset.modOptions.constants.localHappinessCappedByPopulation = false
        val city = city(2, "Colosseum", "Zoo", "Circus")
        assertNull(city.cityStats.happinessList[limitKey])
        assertEquals(6f, city.cityStats.localHappiness, 0.001f)
    }
}
