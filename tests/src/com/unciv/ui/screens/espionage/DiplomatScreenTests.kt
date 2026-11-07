package com.unciv.ui.screens.espionage

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class DiplomatScreenTests {
    @Test
    fun `human can assign a diplomat from the espionage screen`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(6)
        val us = game.addCiv(isPlayer = true)
        val other = game.addCiv()
        game.gameInfo.currentPlayerCiv = us
        game.addCity(us, game.tileMap[0, 0], replacePalace = true)
        val capital = game.addCity(other, game.tileMap[0, 4], replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(other)
        us.getDiplomacyManager(other)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(us, true)
        val spy = us.espionageManager.addSpy()
        UiTestSupport.prepare(game)
        val world = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(world.canChangeState).thenReturn(true)
        val screen = EspionageScreen(us, world)
        try {
            val move = UiTestSupport.actors(screen.stage).filterIsInstance<TextButton>()
                .first { UiTestSupport.labelTexts(it).any { text -> text == "Move" } }
            assertTrue(!move.isDisabled)
            move.listeners.filterIsInstance<ActivationListener>().first()
                .tap(InputEvent().apply { listenerActor = move }, 0f, 0f, 1, 0)
            val diplomatButton = UiTestSupport.actors(screen.stage).filterIsInstance<Button>()
                .first { UiTestSupport.labelTexts(it).any { text -> text == "Diplomat" } && it.isVisible }
            diplomatButton.listeners.filterIsInstance<ActivationListener>().first()
                .tap(InputEvent().apply { listenerActor = diplomatButton }, 0f, 0f, 1, 0)
            assertEquals(capital, spy.getCityOrNull())
            assertTrue(spy.isDiplomat)
            assertEquals(SpyAction.Moving, spy.action)
        } finally {
            screen.dispose()
        }
    }
}
