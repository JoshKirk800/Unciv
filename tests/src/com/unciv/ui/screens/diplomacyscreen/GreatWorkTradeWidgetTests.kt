package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class GreatWorkTradeWidgetTests {
    @Test
    fun `a Great Work appears in human trade offers and can be selected by its identity`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(6)
        val us = game.addCiv(isPlayer = true)
        val other = game.addCiv()
        game.gameInfo.currentPlayerCiv = us
        val home = game.addCity(us, game.tileMap[0, 0])
        game.addCity(other, game.tileMap[0, 4])
        us.diplomacyFunctions.makeCivilizationsMeet(other)
        us.getDiplomacyManager(other)!!.makePeace()
        val gallery = game.createBuilding("Has [1] [Art/Artifact] Great Work slots")
        home.cityConstructions.addBuilding(gallery)
        val work = game.createGreatWork()
        val instance = GreatWorkInstance(work.name, us.civName, "Ancient era", 1)
        home.greatWorks.place(instance, GreatWorkSlot(home, gallery, 0))
        UiTestSupport.prepare(game)
        val trade = TradeLogic(us, other)
        val offer = trade.ourAvailableOffers.single { it.type == TradeOfferType.GreatWork }
        val view = GameView(game.gameInfo, us)
        val selected = arrayListOf<String>()
        val list = OffersListScroll("greatwork-smoke") { selected.add(it.name) }
        list.update(trade.ourAvailableOffers, trade.theirAvailableOffers,
            ourCiv = view.civView, theirCiv = view.getForeignCivView(other))
        val labels = UiTestSupport.labelTexts(list)
        assertTrue(labels.toString(), "Great Works" in labels)
        val button = UiTestSupport.actors(list).filterIsInstance<Button>()
            .first { widget -> UiTestSupport.labelTexts(widget).any { work.name in it } }
        button.listeners.filterIsInstance<ActivationListener>().first()
            .tap(InputEvent().apply { listenerActor = button }, 0f, 0f, 1, 0)
        assertEquals(listOf(instance.id), selected)
        assertEquals(instance.id, offer.name)
    }
}
