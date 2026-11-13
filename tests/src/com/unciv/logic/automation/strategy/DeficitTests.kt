package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvTreasury::DoGold calling CvPlayer::DoDeficit: with the treasury after the turn's income at or below DEFICIT_UNIT_DISBANDING_THRESHOLD
 *  (-5 gold), a civilization with more military units than cities disbands the weakest one in half of the cases.
 */
@RunWith(GdxTestRunner::class)
class DeficitTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, Constants.grassland)
        civ = game.addCiv()
        game.addCity(civ, game.tileMap[HexCoord(0, 0)])
    }

    /** [count] warriors on free plots, enough of them (against the reserve and the recommended size) for a defense state of ENOUGH */
    private fun warriors(count: Int): List<MapUnit> {
        val plots = game.tileMap.values.filter { it.position != HexCoord(0, 0) && it.militaryUnit == null }.take(count)
        return plots.map { game.addUnit("Warrior", civ, it) }
    }

    private fun militaryUnits() = civ.units.getCivUnits().count { it.isMilitary() }

    /** Ends the civilization's turn with its treasury so that the gold after the turn's income is [goldAfter] (the income's fraction on top); true if a unit was disbanded */
    private fun endTurnLeaving(goldAfter: Int, turn: Int): Boolean {
        game.gameInfo.turns = turn
        civ.updateStatsForNextTurn()
        civ.addGold(goldAfter - civ.stats.statsForNextTurn.gold.toInt() - civ.gold)
        val before = militaryUnits()
        TurnManager(civ).endTurn()
        val lost = before - militaryUnits()
        assertTrue("at most one unit a turn", lost <= 1)
        return lost == 1
    }

    private fun disbandingsOver(turns: Int, goldAfter: Int): Int {
        var count = 0
        for (turn in 1..turns) {
            if (endTurnLeaving(goldAfter, turn)) {
                count++
                warriors(1)
            }
        }
        return count
    }

    @Test
    fun `a civilization in deficit disbands one unit in about half of its turns, though it can still build it`() {
        warriors(30)
        val count = disbandingsOver(200, -20)
        assertTrue("$count of 200", count in 60..140)
    }

    @Test
    fun `the unit disbanded is the weakest one`() {
        val units = warriors(30)
        val weakest = units[7].also { it.health = 10 }
        var turn = 0
        while (!endTurnLeaving(-20, ++turn)) assertTrue("never disbanded", turn < 200)
        assertTrue(weakest.isDestroyed)
        assertEquals(29, militaryUnits())
    }

    @Test
    fun `no unit is disbanded while the treasury after the turn stays above the threshold`() {
        warriors(30)
        assertEquals(0, disbandingsOver(100, -4))
        assertTrue(disbandingsOver(100, -6) > 0)
    }

    @Test
    fun `no unit is disbanded when there are not more military units than cities`() {
        val unit = warriors(1).single()
        assertEquals(0, disbandingsOver(100, -50))
        assertFalse(unit.isDestroyed)
    }

    @Test
    fun `a treasury that the income would take below zero is set to 0 in the Civ V rulesets, and a debt remains in rulesets without the constant`() {
        assertTrue(game.ruleset.modOptions.constants.treasuryNeverNegative)
        // CvTreasury::DoGold: SetGold(0) when the gold after the turn's income is below zero
        endTurnLeaving(-40, 1)
        assertEquals(0, civ.gold)
        // and it stays 0 turn after turn, however large the deficit
        endTurnLeaving(-4000, 2)
        assertEquals(0, civ.gold)

        game.ruleset.modOptions.constants.treasuryNeverNegative = false
        endTurnLeaving(-40, 3)
        assertEquals("without the constant the debt stays", true, civ.gold < 0)
    }

    @Test
    fun `a treasury that stays positive after the income is not touched`() {
        endTurnLeaving(25, 1)
        assertEquals(25, civ.gold)
    }
}
