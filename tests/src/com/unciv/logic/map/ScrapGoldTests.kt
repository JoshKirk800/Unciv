package com.unciv.logic.map

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** CvUnit::GetScrapGold: DISBAND_UNIT_REFUND_PERCENT of the production cost, times health left, plus what the unit carries */
@RunWith(BaseTestRunner::class)
class ScrapGoldTests {
    private val game = TestGame()

    private fun unitWithCost(cost: Int) = game.createBaseUnit("Sword").apply { this.cost = cost; strength = 10 }.name

    @Test
    fun `a disbanded unit gives a quarter of its production cost scaled by its health`() {
        game.makeHexagonalMap(3)
        game.ruleset.modOptions.constants.disbandRefundPercent = 25
        val civ = game.addCiv(isPlayer = true)
        game.addCity(civ, game.getTile(0, 0))
        val unit = game.addUnit(unitWithCost(121), civ, game.getTile(1, 0))

        assertEquals("121 x 25 / 100", 30, unit.getScrapGold())
        unit.health = 50
        assertEquals("30 x 50 / 100", 15, unit.getScrapGold())
    }

    @Test
    fun `disbanding in own territory pays the scrap gold`() {
        game.makeHexagonalMap(3)
        game.ruleset.modOptions.constants.disbandRefundPercent = 25
        val civ = game.addCiv(isPlayer = true)
        game.addCity(civ, game.getTile(0, 0))
        val unit = game.addUnit(unitWithCost(200), civ, game.getTile(1, 0))
        val before = civ.gold

        unit.disband()

        assertEquals(50, civ.gold - before)
    }
}
