package com.unciv.logic.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvTreasury::CalculateUnitCost with the BNW GlobalDefines: 0.5 gold per unit, none free,
 *  (paid x 0.5 x (1 + 8 x progress)) ^ (1 + progress / 7), progress = turn / turns of the game speed */
@RunWith(GdxTestRunner::class)
class UnitMaintenanceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        game.setDifficulty("Prince") // a human of this level pays 100 % of the unit cost
        civ = game.addCiv(isPlayer = true)
        game.addCity(civ, game.tileMap[0, 0])
    }

    /** The unit upkeep line of the empire's gold per turn, as a positive number */
    private fun upkeep() = -civ.stats.getStatMapForNextTurn()["Unit upkeep"]!!.gold.toInt()

    private fun addWarriors(count: Int) = repeat(count) { game.addUnit("Warrior", civ, null) }

    @Test
    fun `no unit is free for maintenance`() {
        game.gameInfo.turns = 0
        addWarriors(3)
        // 3 units x 0.5 gold, truncated
        assertEquals(1, upkeep())
    }

    @Test
    fun `halfway through the game ten units cost 31 gold`() {
        assertEquals(500, game.gameInfo.speed.numTotalTurns())
        game.gameInfo.turns = 250
        addWarriors(10)
        // (10 x 0.5 x (1 + 8 x 0.5)) ^ (1 + 0.5 / 7) = 25 ^ 1.0714 = 31.5
        assertEquals(31, upkeep())
    }

    @Test
    fun `at turn 100 twelve units cost 16 gold`() {
        game.gameInfo.turns = 100
        addWarriors(12)
        // (12 x 0.5 x (1 + 8 x 0.2)) ^ (1 + 0.2 / 7) = 15.6 ^ 1.0286 = 16.9
        assertEquals(16, upkeep())
    }
}
