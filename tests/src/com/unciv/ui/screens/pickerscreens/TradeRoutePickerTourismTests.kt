package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TradeRoutePickerTourismTests {
    @Test
    fun `international route picker shows the influence gained from the first connection`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(8)
        val trader = game.addCiv("[+4] Trade Route slots", isPlayer = true)
        val partner = game.addCiv()
        game.gameInfo.currentPlayerCiv = trader
        val origin = game.addCity(trader, game.tileMap[0, 0])
        game.addCity(partner, game.tileMap[0, 5])
        trader.diplomacyFunctions.makeCivilizationsMeet(partner)
        trader.getDiplomacyManager(partner)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(trader, true)
        val gallery = game.createBuilding("Has [5] [Art/Artifact] Great Work slots")
        origin.cityConstructions.addBuilding(gallery)
        repeat(5) { index ->
            val work = game.createGreatWork()
            origin.greatWorks.place(GreatWorkInstance(work.name, trader.civName, "Ancient era", 1), GreatWorkSlot(origin, gallery, index))
        }
        val caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        val unit = game.addUnit(caravan.name, trader, origin.getCenterTile())
        UiTestSupport.prepare(game)
        val picker = TradeRoutePickerScreen(unit)
        try {
            val row = UiTestSupport.actors(picker.topTable).filterIsInstance<Button>()
                .first { button -> UiTestSupport.labelTexts(button).any { partner.civName in it } }
            val listener = row.listeners.filterIsInstance<ActivationListener>().first()
            listener.tap(InputEvent().apply { listenerActor = row }, 0f, 0f, 1, 0)
            val text = picker.descriptionLabel.text.toString()
            val before = trader.culture.getInfluencePerTurn(partner)
            val gain = trader.culture.getTourismIncreaseFromTradeConnection(partner)
            assertTrue(text, gain > 0)
            assertTrue(text, "Tourism towards ${partner.civName}: $before -> ${before + gain} per turn" in UiTestSupport.plain(text))
        } finally {
            picker.dispose()
        }
    }
}
