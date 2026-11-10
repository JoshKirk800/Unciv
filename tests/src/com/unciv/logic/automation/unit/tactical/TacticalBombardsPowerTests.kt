package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** CvTacticalAI::ScoreAssignments adds CvUnit::GetPower to the score of a candidate: of two units at the same distance the more powerful one is assigned */
@RunWith(GdxTestRunner::class)
class TacticalBombardsPowerTests {
    @Test
    fun theUnitWithTheHigherOriginalPowerIsAssigned() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        val civ = game.addCiv()
        val knight = game.addUnit("Knight", civ, game.getTile(HexCoord(0, 0)))
        val longswordsman = game.addUnit("Longswordsman", civ, game.getTile(HexCoord(1, 0)))
        // 20^1.5 = 89 for the Knight against 21^1.5 = 96: the extra movement of the Knight is worth nothing in the original
        assertTrue(longswordsman.getAiPower() > knight.getAiPower())

        val target = game.getTile(HexCoord(0, 3))
        val blocks = TacticalBombards.assign(
            mapOf(target to listOf(Candidate(knight, 1, 3), Candidate(longswordsman, 1, 3))), canLeaveOpenings = false)!!
        assertEquals(1, blocks.size)
        assertEquals(longswordsman, blocks.single().unit)
    }
}
