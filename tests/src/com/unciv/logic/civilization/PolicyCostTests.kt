package com.unciv.logic.civilization

import com.unciv.logic.map.MapSize
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The cost of the next policy follows CvPlayerPolicies::GetNextPolicyCost: whole-number steps, and the highest
 *  number of cities counts (not the current one), without puppets and cities being razed. Expected values are what
 *  that integer arithmetic gives for 25 + (3n)^2.01 plus 10% per extra city (Brave New World, the Tiny to Medium map size). */
@RunWith(BaseTestRunner::class)
class PolicyCostTests {
    private val game = TestGame()
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv()
    }

    private fun cost(adoptedPolicies: Int) = civ.policies.getPolicyCultureCost(adoptedPolicies)

    private fun foundCities(count: Int) = listOf(game.getTile(0, 0), game.getTile(3, 0), game.getTile(-3, 0), game.getTile(0, 3))
        .take(count).map { game.addCity(civ, it) }

    @Test
    fun `every step truncates to whole numbers`() {
        foundCities(1)
        // 25 + 21^2.01 = 479.6: truncated 479 -> 475, where rounding to nearest gives 480 -> 480
        assertEquals(475, cost(7))
    }

    @Test
    fun `a civilization without cities pays the cost of one`() {
        assertEquals(25, cost(0))
        assertEquals(475, cost(7))
    }

    @Test
    fun `each extra city adds a percentage of the cost`() {
        foundCities(4)
        assertEquals(135, cost(3))
    }

    @Test
    fun `losing a city does not make policies cheaper`() {
        val cities = foundCities(4)
        civ.policies.endTurn(0) // the turn change is where the highest count is recorded
        cities.last().destroyCity()

        assertEquals(3, civ.cities.size)
        assertEquals(135, cost(3))
    }

    @Test
    fun `a city being razed does not count`() {
        val cities = foundCities(4)
        cities.last().isBeingRazed = true

        assertEquals(125, cost(3))
    }

    @Test
    fun `a puppet does not count`() {
        val cities = foundCities(4)
        cities.last().isPuppet = true

        assertEquals(125, cost(3))
    }

    @Test
    fun `larger maps add a smaller percentage per extra city`() {
        foundCities(4)

        game.tileMap.mapParameters.mapSize = MapSize("Large")
        assertEquals(130, cost(3)) // 7.5% per city
        game.tileMap.mapParameters.mapSize = MapSize("Huge")
        assertEquals(120, cost(3)) // 5% per city
    }
}
