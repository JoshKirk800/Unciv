package com.unciv.logic.civilization.managers

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The "cities auto-bombard at end of turn" setting is a human's: AI cities shoot through the tactical AI (CvTacticalAI) */
@RunWith(BaseTestRunner::class)
class AutoBombardTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private val wasSet = UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn

    @After
    fun restore() { UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = wasSet }

    private fun enemyHealthAfterEndTurn(human: Boolean): Int {
        UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn = true
        game.makeHexagonalMap(6, Constants.grassland)
        val civ = game.addCiv(isPlayer = human)
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addCity(civ, game.getTile(0, 0))
        val victim = game.addUnit("Warrior", enemy, game.getTile(2, 0))
        civ.viewableTiles = game.tileMap.values.toSet()
        TurnManager(civ).endTurn()
        return victim.health
    }

    @Test
    fun `a human's city bombards at the end of the turn when the setting is on`() =
        assertTrue(enemyHealthAfterEndTurn(human = true) < 100)

    @Test
    fun `an AI civ's cities do not, whatever the setting`() = assertEquals(100, enemyHealthAfterEndTurn(human = false))
}
