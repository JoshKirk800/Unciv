package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Brave New World's social policy screen: the ideologies are a screen of their own, with tenet icons that do not overlap */
@RunWith(GdxTestRunner::class)
class PolicyPickerIdeologyScreenTests {
    private val ideologies = listOf("FREEDOM", "AUTOCRACY", "ORDER")

    private fun civ(): Civilization {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(4)
        val civ = game.addCiv(isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.addCity(civ, game.tileMap[0, 0])
        UiTestSupport.prepare(game)
        return civ
    }

    private fun <T> withScreen(civ: Civilization, showIdeologies: Boolean?, block: (PolicyPickerScreen) -> T): T {
        val screen = PolicyPickerScreen(civ, true, null, showIdeologies)
        try { return block(screen) } finally { screen.dispose() }
    }

    private fun branchLabels(screen: PolicyPickerScreen) = UiTestSupport.labelTexts(screen.topTable).map { it.uppercase() }

    @Test
    fun `social policies and ideologies are different screens`() {
        val civ = civ()
        withScreen(civ, null) { screen ->
            val labels = branchLabels(screen)
            assertTrue(labels.toString(), "TRADITION" in labels)
            assertTrue(labels.toString(), ideologies.none { it in labels })
            assertTrue("a button to the ideologies", "Ideologies" in UiTestSupport.labelTexts(screen.rightSideGroup))
        }
        withScreen(civ, true) { screen ->
            val labels = branchLabels(screen)
            assertTrue(labels.toString(), ideologies.all { it in labels })
            assertFalse(labels.toString(), "TRADITION" in labels)
            assertTrue("a button back", "Social policies" in UiTestSupport.labelTexts(screen.rightSideGroup))
        }
    }

    @Test
    fun `the tenets of an ideology are laid out without overlapping`() {
        withScreen(civ(), true) { screen ->
            val buttons = UiTestSupport.actors(screen.topTable).filter { it.javaClass.simpleName == "PolicyButton" }
            assertEquals("three ideologies of 16 tenets", 48, buttons.size)
            val rects = buttons.map { bounds(it) }
            for (i in rects.indices) for (j in i + 1 until rects.size)
                assertFalse("tenet $i and $j overlap: ${rects[i]} ${rects[j]}", rects[i].overlaps(rects[j]))
        }
    }

    @Test
    fun `the three ideologies fit the default screen width side by side`() {
        withScreen(civ(), true) { screen ->
            screen.topTable.pack()
            // GameSettings.ScreenSize.Small, the default: 900 x 600 minimum, so about 1070 wide on a 16:9 screen
            assertTrue("width ${screen.topTable.width}", screen.topTable.width <= 1070f)
        }
    }

    private fun bounds(actor: Actor): Rectangle {
        val bottomLeft = actor.localToStageCoordinates(com.badlogic.gdx.math.Vector2(0f, 0f))
        return Rectangle(bottomLeft.x, bottomLeft.y, actor.width, actor.height)
    }
}
