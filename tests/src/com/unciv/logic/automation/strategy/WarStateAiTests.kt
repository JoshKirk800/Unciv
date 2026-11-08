package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvDiplomacyAI::DoUpdateWarStates / GetStateAllWars on built states (BNW defines) */
@RunWith(GdxTestRunner::class)
class WarStateAiTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        us = game.addCiv()
        them = game.addCiv()
        game.addCity(us, game.tileMap[-7, 0])
        game.addCity(them, game.tileMap[7, 0])
        us.diplomacyFunctions.makeCivilizationsMeet(them)
    }

    private fun warriors(owner: Civilization, x: Int, y: Int) = game.addUnit("Warrior", owner, game.tileMap[x, y])

    @Test
    fun `no war, no war state`() {
        WarStateAi.doTurn(us)
        assertNull(WarStateAi.warState(us, them))
        assertEquals(StateAllWars.Neutral, WarStateAi.stateAllWars(us))
        assertEquals(0, WarStateAi.numberCivsAtWarWith(us))
    }

    @Test
    fun `our army on their home front with none of theirs is a war nearly won`() {
        us.getDiplomacyManager(them)!!.declareWar()
        warriors(us, 6, 0)
        warriors(us, 6, 1)
        WarStateAi.doTurn(us)
        // local ratio 100 (no enemy near us), foreign ratio ~100 (their cities' power rounds to 0): (100 + 100) / 2 >= 75
        assertEquals(WarState.NearlyWon, WarStateAi.warState(us, them))
        assertEquals(StateAllWars.Winning, WarStateAi.stateAllWars(us))
        assertEquals(1, WarStateAi.numberCivsAtWarWith(us))
        assertEquals(1, WarStateAi.turnsAtWar(us, them))
    }

    @Test
    fun `their army on our home front with none of ours is a war nearly lost`() {
        us.getDiplomacyManager(them)!!.declareWar()
        warriors(them, -6, 0)
        warriors(them, -6, 1)
        WarStateAi.doTurn(us)
        // local ratio ~0, foreign ratio 0: nearly defeated, which makes the overall state losing
        assertEquals(WarState.NearlyDefeated, WarStateAi.warState(us, them))
        assertEquals(StateAllWars.Losing, WarStateAi.stateAllWars(us))
    }

    @Test
    fun `a few enemies near us while we vastly outnumber them is a stalemate, not defense`() {
        us.getDiplomacyManager(them)!!.declareWar()
        val around = game.tileMap[-7, 0].neighbors.toList()
        game.addUnit("Warrior", them, around[0])
        for (tile in around.drop(1).take(3)) game.addUnit("Warrior", us, tile)
        WarStateAi.doTurn(us)
        // local: ours 3 vs theirs 1 → ratio 75 (not calm); foreign 0 → value 37 (defensive), but 3 >= 2 × 1
        assertEquals(WarState.Stalemate, WarStateAi.warState(us, them))
        assertEquals(StateAllWars.Neutral, WarStateAi.stateAllWars(us))
    }
}
