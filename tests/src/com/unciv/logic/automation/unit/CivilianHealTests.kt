package com.unciv.logic.automation.unit

import com.unciv.UncivGame
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvHomelandAI::PlotHealMoves for the civilians no earlier homeland move takes: a hurt worker waits to heal */
@RunWith(BaseTestRunner::class)
class CivilianHealTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var mine: Tile

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = true
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        for (improvement in listOf("Farm", "Mine")) civ.tech.techsResearched.add(game.ruleset.tileImprovements[improvement]!!.techRequired!!)
        civ.tech.techsResearched.add(game.ruleset.tileResources["Iron"]!!.revealedBy!!)
        game.addCity(civ, game.tileMap[0, 0])
        mine = game.tileMap[1, 1] // an iron tile inside the borders: the one thing a worker has to do
        mine.setTileResource("Iron")
    }

    private fun automate(unit: MapUnit) =
        CivilianUnitAutomation.automateCivilianUnit(GameView(civ.gameInfo, civ).getMapUnitView(unit), AutomationCache())

    @Test
    fun `a worker with full health goes to work`() {
        val worker = game.addUnit("Worker", civ, mine)
        automate(worker)
        assertNotNull(mine.improvementInProgress)
    }

    @Test
    fun `a hurt worker outside a city waits where it is to heal`() {
        val worker = game.addUnit("Worker", civ, mine)
        worker.health = 60
        automate(worker)
        assertNull(mine.improvementInProgress)
        assertEquals(mine, worker.getTile())
        assertEquals(0f, worker.currentMovement)
    }
}
