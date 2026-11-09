package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Where barbarian camps may be placed (CvBarbarians::DoCamps) */
@RunWith(BaseTestRunner::class)
class BarbarianCampPlacementTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun setUpMapOwnedBy(owned: Boolean) {
        game.makeHexagonalMap(8, Constants.grassland)
        game.addBarbarianCiv()
        val cityState = game.addCiv(cityStateType = "Cultured")
        val city = game.addCity(cityState, game.getTile(-8, 0))
        if (owned) for (tile in game.tileMap.values) if (tile != city.getCenterTile()) tile.setOwningCity(city)
        game.gameInfo.barbarians.setTransients(game.gameInfo)
    }

    @Test
    fun `a camp is placed on unowned land`() {
        setUpMapOwnedBy(owned = false)
        game.gameInfo.barbarians.placeBarbarianEncampment(forTesting = true)
        assertEquals(1, game.gameInfo.barbarians.encampments.size)
    }

    @Test
    fun `no camp is placed on land inside someone's borders, however far out of sight`() {
        setUpMapOwnedBy(owned = true)
        game.gameInfo.barbarians.placeBarbarianEncampment(forTesting = true)
        assertTrue(game.gameInfo.barbarians.encampments.isEmpty())
    }
}
