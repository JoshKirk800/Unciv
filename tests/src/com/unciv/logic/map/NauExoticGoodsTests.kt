package com.unciv.logic.map

import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class NauExoticGoodsTests {
    @Test
    fun `a Nau sells cargo next to peaceful foreign territory only once`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        val portugal = game.addCiv(game.ruleset.nations["Portugal"]!!)
        val neighbor = game.addCiv(cityStateType = "Maritime")
        portugal.diplomacyFunctions.makeCivilizationsMeet(neighbor)
        portugal.getDiplomacyManager(neighbor)!!.makePeace()
        val capital = game.addCity(portugal, game.getTile(0, 0))
        game.addCity(neighbor, game.getTile(0, 6))
        val sea = game.getTile(0, 4)
        sea.baseTerrain = "Coast"
        sea.setTerrainTransients()
        val nau = game.addUnit("Nau", portugal, sea)
        val possible = { UnitActions.getUnitActions(nau, UnitActionType.SellExoticGoods).firstOrNull() }
        assertTrue(possible()?.action != null)
        val dimensions = game.tileMap.mapParameters.mapSize
        val threshold = (dimensions.width + dimensions.height) / 2
        val distance = sea.aerialDistanceTo(capital.getCenterTile())
        val goldBefore = portugal.gold
        val xpBefore = nau.promotions.XP
        val movesBefore = nau.currentMovement
        possible()!!.action!!.invoke()
        assertEquals(goldBefore + (100 + 300 * distance / threshold).coerceAtMost(400), portugal.gold)
        assertEquals(xpBefore + (10 + 20 * distance / threshold).coerceAtMost(30), nau.promotions.XP)
        assertEquals(movesBefore, nau.currentMovement)
        assertEquals(1, nau.clone().abilityToTimesUsed.values.single())
        assertTrue(possible() == null)
    }

    @Test
    fun `a Nau cannot sell without peaceful foreign land or while at war`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        val portugal = game.addCiv(game.ruleset.nations["Portugal"]!!)
        val neighbor = game.addCiv()
        portugal.diplomacyFunctions.makeCivilizationsMeet(neighbor)
        portugal.getDiplomacyManager(neighbor)!!.makePeace()
        game.addCity(portugal, game.getTile(0, 0))
        val sea = game.getTile(0, 4)
        sea.baseTerrain = "Coast"
        sea.setTerrainTransients()
        val nau = game.addUnit("Nau", portugal, sea)
        fun enabled() = UnitActions.getUnitActions(nau, UnitActionType.SellExoticGoods).firstOrNull()?.action != null
        assertFalse(enabled())
        game.addCity(neighbor, game.getTile(0, 6))
        assertTrue(enabled())
        portugal.getDiplomacyManager(neighbor)!!.declareWar()
        assertFalse(enabled())
        portugal.getDiplomacyManager(neighbor)!!.makePeace()
        assertTrue(enabled())
    }

    @Test
    fun `AI Nau sells only after it has sailed far enough from its capital`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        val portugal = game.addCiv(game.ruleset.nations["Portugal"]!!)
        val neighbor = game.addCiv()
        portugal.diplomacyFunctions.makeCivilizationsMeet(neighbor)
        portugal.getDiplomacyManager(neighbor)!!.makePeace()
        game.addCity(portugal, game.getTile(0, 0))
        game.addCity(neighbor, game.getTile(0, 6))
        game.addCity(neighbor, game.getTile(0, 12))
        val nearTile = game.getTile(0, 4)
        val farTile = game.getTile(0, 11)
        for (tile in listOf(nearTile, farTile)) {
            tile.baseTerrain = "Coast"
            tile.setTerrainTransients()
            tile.setOwningCity(null)
        }
        val nearNau = game.addUnit("Nau", portugal, nearTile)
        val farNau = game.addUnit("Nau", portugal, farTile)
        val threshold = (game.tileMap.mapParameters.mapSize.width + game.tileMap.mapParameters.mapSize.height) / 2
        assertTrue(2 * nearTile.aerialDistanceTo(portugal.getCapital()!!.getCenterTile()) < threshold)
        assertTrue(2 * farTile.aerialDistanceTo(portugal.getCapital()!!.getCenterTile()) >= threshold)
        com.unciv.logic.map.mapunit.actions.UnitActionsExoticGoods.tryAutoSell(nearNau)
        assertTrue(nearNau.abilityToTimesUsed.isEmpty())
        com.unciv.logic.map.mapunit.actions.UnitActionsExoticGoods.tryAutoSell(farNau)
        assertEquals(1, farNau.abilityToTimesUsed.values.single())
    }
}
