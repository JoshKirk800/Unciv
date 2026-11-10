package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvCity::GetJONSCultureThreshold with the GlobalDefines CULTURE_COST_FIRST_PLOT 15, ..._LATER_PLOT_MULTIPLIER 10, ..._EXPONENT 1.1,
 *  MINOR_CIV_PLOT_CULTURE_COST_MULTIPLIER 150, CULTURE_PLOT_COST_MOD_MINIMUM -85 and CULTURE_COST_VISIBLE_DIVISOR 5 */
@RunWith(GdxTestRunner::class)
class CultureCostLikeCivVTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        civ = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord.Zero))
    }

    private fun costAtLevel(level: Int): Int {
        city.expansion.cultureLevel = level
        return city.expansion.getCultureToNextTile()
    }

    @Test
    fun `cost is 15 plus (10 x level) to the 1,1, rounded down to 5`() {
        // 15, 15 + int(12.59) = 27, 15 + int(26.9) = 41, 15 + int(73.7) = 88, 15 + int(158.5) = 173, 15 + int(339.8) = 354
        assertEquals(listOf(15, 25, 40, 85, 170, 350), listOf(0, 1, 2, 5, 10, 20).map(::costAtLevel))
    }

    @Test
    fun `a city-state pays one and a half times before the rounding`() {
        val cityStateCiv = game.addCiv(cityStateType = "Militaristic")
        val cityState = game.addCity(cityStateCiv, game.getTile(5, 0))
        assertEquals(20, cityState.expansion.getCultureToNextTile()) // 15 x 150 / 100 = 22
        cityState.expansion.cultureLevel = 5
        assertEquals(130, cityState.expansion.getCultureToNextTile()) // 88 x 150 / 100 = 132
    }

    @Test
    fun `percentages are added up and the sum is at least -85`() {
        val frugal = game.addCiv("[-25]% Culture cost of natural border growth [in all cities]",
            "[-15]% Culture cost of natural border growth [in all cities]")
        val frugalCity = game.addCity(frugal, game.getTile(5, 0))
        frugalCity.expansion.cultureLevel = 5
        assertEquals(50, frugalCity.expansion.getCultureToNextTile()) // 88 x 60 / 100 = 52, not 88 x 0.75 x 0.85 = 56 -> 55

        val free = game.addCiv("[-100]% Culture cost of natural border growth [in all cities]")
        val freeCity = game.addCity(free, game.getTile(-5, 0))
        assertEquals(2, freeCity.expansion.getCultureToNextTile()) // 15 x 15 / 100
    }

    @Test
    fun `only tiles gained by culture raise the cost, not bought ones`() {
        val expansion = city.expansion
        val first = expansion.getCultureToNextTile()
        civ.addGold(1000)
        expansion.buyTile(game.getTile(2, 0))
        assertEquals(first, expansion.getCultureToNextTile())

        expansion.nextTurn(first.toFloat())
        assertEquals(1, expansion.cultureLevel)
        assertEquals(25, expansion.getCultureToNextTile())
    }

    @Test
    fun `a saved city without the counter takes its level from the tiles it claimed`() {
        city.expansion.buyTile(game.getTile(2, 0).also { civ.addGold(1000) })
        city.expansion.cultureLevel = -1
        city.expansion.setTransients()
        assertEquals(1, city.expansion.cultureLevel)
    }
}
