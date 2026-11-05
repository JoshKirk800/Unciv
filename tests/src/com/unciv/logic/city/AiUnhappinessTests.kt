package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** An AI civilization suffers the unhappiness of the Chieftain handicap (60%, AI_HANDICAP) scaled by the game
 *  difficulty's AIUnhappinessPercent (CvPlayer::GetUnhappiness): in Gods & Kings 100% up to King, 90% Emperor, 85% Immortal, 75% Deity. */
@RunWith(BaseTestRunner::class)
class AiUnhappinessTests {
    private val game = TestGame()

    private fun aiCityUnhappiness(difficulty: String): Float {
        game.setDifficulty(difficulty)
        game.makeHexagonalMap(2)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
        city.cityStats.update()
        return city.cityStats.happinessList["Cities"]!!
    }

    @Test
    fun `the AI unhappiness percentage of the game difficulty follows the G&K handicaps`() {
        val atKing = aiCityUnhappiness("King")
        assertEquals("King has no AI reduction", -3f * 0.6f, atKing, 0.001f)
        assertEquals("Emperor", atKing * 0.9f, aiCityUnhappiness("Emperor"), 0.001f)
        assertEquals("Immortal", atKing * 0.85f, aiCityUnhappiness("Immortal"), 0.001f)
        assertEquals("Deity", atKing * 0.75f, aiCityUnhappiness("Deity"), 0.001f)
    }
}
