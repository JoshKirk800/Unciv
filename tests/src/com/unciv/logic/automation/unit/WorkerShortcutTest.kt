package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bail-out of CvBuilderTaskingAI::EvaluateBuilder ([WorkerAutomation.executeWorkerMove]): a worker that is already building
 * keeps building instead of walking to a (possibly better) tile.
 */
@RunWith(BaseTestRunner::class)
internal class WorkerShortcutTest {
    private val testGame = TestGame()
    private lateinit var civInfo: Civilization
    private lateinit var workerAutomation: WorkerAutomation

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = true
        testGame.makeHexagonalMap(7)
        civInfo = testGame.addCiv()
        workerAutomation = WorkerAutomation(civInfo, 3)
        for (improvement in listOf("Road", "Farm", "Mine"))
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        for (tile in testGame.tileMap[0, 0].getTilesInDistance(3))
            tile.baseTerrain = Constants.grassland
    }

    /** A tile with a luxury resource that needs a mine - the best thing around, so an idle worker goes there. */
    private fun makeAttractiveTile(tile: Tile) {
        tile.addTerrainFeature(Constants.hill)
        tile.setTileResource("Gold Ore")
    }

    private fun workerWorkingOn(tile: Tile, improvement: String): MapUnit {
        val worker = testGame.addUnit("Worker", civInfo, tile)
        worker.currentMovement = 2f
        tile.startWorkingOnImprovement(testGame.ruleset.tileImprovements[improvement]!!, civInfo, worker)
        assertEquals("setup", improvement, tile.improvementInProgress)
        return worker
    }

    private fun automate(worker: MapUnit) =
        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(worker), hashSetOf())

    @Test
    fun `a worker on a suitable tile finishes its improvement instead of walking to a better one`() {
        makeAttractiveTile(testGame.tileMap[-1, 0])
        val here = testGame.tileMap[1, 0]
        val worker = workerWorkingOn(here, "Farm")

        automate(worker)

        assertEquals(here, worker.getTile())
        assertEquals("Farm", here.improvementInProgress)
    }

    @Test
    fun `a worker building a road outside our borders keeps building it instead of walking to a better tile`() {
        val attractive = testGame.tileMap[1, 1].also(::makeAttractiveTile)
        val outside = testGame.tileMap[3, 3]
        assertEquals("setup: outside our borders", null, outside.getOwner())
        val worker = workerWorkingOn(outside, "Road")

        automate(worker)

        assertEquals("the attractive tile at ${attractive.position} would otherwise draw the worker away", outside, worker.getTile())
        assertEquals("Road", outside.improvementInProgress)
    }
}
