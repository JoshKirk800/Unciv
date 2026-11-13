package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** CvUnitMovement::GetCostsForMove: a feature on a hill costs the feature's movement plus HILLS_EXTRA_MOVEMENT (1) */
@RunWith(BaseTestRunner::class)
class ForestedHillMovementTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun costInto(vararg features: String): Float {
        game.makeHexagonalMap(2)
        val civ = game.addCiv()
        val to = game.setTileFeatures(HexCoord(1, 0), *features)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        return MovementCost.getMovementCostBetweenAdjacentTiles(unit, game.getTile(0, 0), to, considerZoneOfControl = false)
    }

    @Test
    fun `entering a forested hill costs 3`() {
        assertEquals(3f, costInto(Constants.hill, Constants.forest), 0f)
    }

    @Test
    fun `entering a hill costs 2 and a forest costs 2`() {
        assertEquals(2f, costInto(Constants.hill), 0f)
        assertEquals(2f, costInto(Constants.forest), 0f)
    }
}
