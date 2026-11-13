package com.unciv.logic.city

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** The gold for selling a building ([City.getGoldForSellingBuilding]). */
@RunWith(BaseTestRunner::class)
class BuildingSaleRefundTests {
    private val game = TestGame()

    private fun cityWithBuilding(cost: Int, vararg uniques: String): Pair<City, String> {
        game.makeHexagonalMap(2)
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(0, 0))
        val building = game.createBuilding(*uniques)
        building.cost = cost
        return city to building.name
    }

    @Test
    fun civVDivisorUsesTheProductionCostDividedByFour() {
        game.ruleset.modOptions.constants.buildingSaleDivisor = 4
        // Cost 100 with a cost modifier of +50% is 150 production, sold for 37
        val (city, name) = cityWithBuilding(100, "[+50]% production cost")
        Assert.assertEquals(37, city.getGoldForSellingBuilding(name))
    }

    @Test
    fun divisorTenKeepsTheRawCostOverTen() {
        game.ruleset.modOptions.constants.buildingSaleDivisor = 10
        val (city, name) = cityWithBuilding(100, "[+50]% production cost")
        Assert.assertEquals(10, city.getGoldForSellingBuilding(name))
    }
}
