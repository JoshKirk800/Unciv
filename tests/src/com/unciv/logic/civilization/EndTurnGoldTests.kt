package com.unciv.logic.civilization

import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** What [TurnManager.endTurn] does with the treasury: income, expenses, and disbanding military units when broke. */
@RunWith(BaseTestRunner::class)
class EndTurnGoldTests {
    private val game = TestGame()

    /** A player civ with one city whose gold per turn is [goldPerCity] (negative = costs), via a global unique. */
    private fun civWithCity(goldPerCity: Int): Civilization {
        game.makeHexagonalMap(2)
        val sign = if (goldPerCity < 0) "" else "+"
        val civ = game.addCiv("[$sign$goldPerCity Gold] [in all cities]", isPlayer = true)
        game.addCity(civ, game.getTile(0, 0))
        return civ
    }

    private fun predictedGoldIncome(civ: Civilization): Int {
        civ.updateStatsForNextTurn()
        return civ.stats.statsForNextTurn.gold.toInt()
    }

    /** Two military units (one per tile, inside the city's borders) and one civilian. */
    private fun addWarriorsAndAWorker(civ: Civilization) {
        game.addUnit("Warrior", civ, game.getTile(1, 0))
        game.addUnit("Warrior", civ, game.getTile(0, 1))
        game.addUnit("Worker", civ, game.getTile(0, 0))
    }

    private fun Civilization.militaryUnits() = units.getCivUnits().count { it.isMilitary() }
    private fun Civilization.civilianUnits() = units.getCivUnits().count { it.isCivilian() }

    @Test
    fun incomeIsAddedToTheTreasury() {
        val civ = civWithCity(50)
        civ.addGold(100)
        val income = predictedGoldIncome(civ)
        Assert.assertTrue("setup: positive income, was $income", income >= 50)

        TurnManager(civ).endTurn()

        Assert.assertEquals(100 + income, civ.gold)
    }

    @Test
    fun negativeIncomeIsDeducted() {
        val civ = civWithCity(-100)
        civ.addGold(1000)
        val income = predictedGoldIncome(civ)
        Assert.assertTrue("setup: negative income, was $income", income < 0)

        TurnManager(civ).endTurn()

        Assert.assertEquals(1000 + income, civ.gold)
    }

    @Test
    fun militaryUnitsAreDisbandedWhenFarBelowTheLimitWithNegativeIncome() {
        val civ = civWithCity(-100)
        addWarriorsAndAWorker(civ)
        civ.addGold(-1000)
        Assert.assertTrue("setup", predictedGoldIncome(civ) < 0)

        TurnManager(civ).endTurn()

        Assert.assertEquals("military units should be disbanded one by one until none are left", 0, civ.militaryUnits())
        Assert.assertEquals("civilians are never disbanded for upkeep", 1, civ.civilianUnits())
    }

    @Test
    fun disbandingStartsAtExactlyMinus200Gold() {
        val civ = civWithCity(-100)
        addWarriorsAndAWorker(civ)
        civ.addGold(-200)
        Assert.assertTrue("setup", predictedGoldIncome(civ) < 0)

        TurnManager(civ).endTurn()

        // Disbanding a unit inside our borders refunds some gold, which may already end the process after one unit
        Assert.assertTrue("at least one military unit should be disbanded, ${civ.militaryUnits()} left", civ.militaryUnits() < 2)
        Assert.assertEquals(1, civ.civilianUnits())
    }

    @Test
    fun noUnitsAreDisbandedJustAboveTheLimit() {
        val civ = civWithCity(-100)
        addWarriorsAndAWorker(civ)
        civ.addGold(-199)
        Assert.assertTrue("setup", predictedGoldIncome(civ) < 0)

        TurnManager(civ).endTurn()

        Assert.assertEquals(2, civ.militaryUnits())
    }

    @Test
    fun noUnitsAreDisbandedWhenIncomeIsPositiveEvenFarBelowTheLimit() {
        val civ = civWithCity(50)
        addWarriorsAndAWorker(civ)
        civ.addGold(-500)
        Assert.assertTrue("setup", predictedGoldIncome(civ) > 0)

        TurnManager(civ).endTurn()

        Assert.assertEquals(2, civ.militaryUnits())
    }
}
