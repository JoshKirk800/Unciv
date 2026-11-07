package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.view.GameView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class IdeologyPoliticsOverviewTests {
    private lateinit var game: TestGame
    private lateinit var viewer: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        viewer = game.addCiv(isPlayer = true)
        game.addCity(viewer, game.tileMap[0, 0], replacePalace = true)
        UiTestSupport.prepare(game)
    }

    private fun newMajor(x: Int, y: Int): Civilization = game.addCiv().also {
        game.addCity(it, game.tileMap[x, y], replacePalace = true)
    }

    private fun choose(civ: Civilization, ideology: String) {
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        civ.policies.adopt(game.ruleset.policyBranches[ideology]!!)
    }

    private fun politicsTexts(): List<String> {
        val politics = GlobalPoliticsOverviewTable(
            GameView(game.gameInfo, viewer).civView,
            Mockito.mock(EmpireOverviewScreen::class.java)
        )
        return UiTestSupport.labelTexts(politics)
    }

    @Test
    fun `politics shows ideology of our civilization and met majors but not strangers`() {
        val met = newMajor(0, 6)
        val stranger = newMajor(0, -6)
        viewer.diplomacyFunctions.makeCivilizationsMeet(met)
        choose(viewer, "Autocracy")
        choose(met, "Freedom")
        choose(stranger, "Order")

        val labels = politicsTexts()
        assertTrue(labels.toString(), "Ideology: Autocracy" in labels)
        assertTrue(labels.toString(), "Ideology: Freedom" in labels)
        assertFalse(labels.toString(), labels.any { "Order" in it })
        assertFalse(labels.toString(), labels.any { stranger.civName in it })
    }

    @Test
    fun `politics leaves ideology blank when a met civilization has not chosen one`() {
        val met = newMajor(0, 6)
        viewer.diplomacyFunctions.makeCivilizationsMeet(met)

        val labels = politicsTexts()
        assertTrue(labels.toString(), met.civName in labels)
        assertFalse(labels.toString(), labels.any { "Ideology:" in it })
    }

    @Test
    fun `politics still displays known civilizations without ideology in a GnK game`() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_GnK)
        game.makeHexagonalMap(8)
        viewer = game.addCiv(isPlayer = true)
        game.addCity(viewer, game.tileMap[0, 0], replacePalace = true)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        val met = newMajor(0, 6)
        viewer.diplomacyFunctions.makeCivilizationsMeet(met)
        UiTestSupport.prepare(game)

        val labels = politicsTexts()
        assertTrue(labels.toString(), met.civName in labels)
        assertFalse(labels.toString(), labels.any { "Ideology:" in it })
    }

}
