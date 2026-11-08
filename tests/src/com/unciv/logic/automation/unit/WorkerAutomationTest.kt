package com.unciv.logic.automation.unit

import com.unciv.view.GameView
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
internal class WorkerAutomationTest {
    private lateinit var workerAutomation: WorkerAutomation
    private lateinit var civInfo: Civilization

    val testGame = TestGame()

    @Before
    fun setUp() {
        UncivGame.Current.settings.useAStarPathfinding = true
        testGame.makeHexagonalMap(7)
        civInfo = testGame.addCiv()
        workerAutomation = WorkerAutomation(civInfo, 3)
    }

    @Test
    fun `should replace already existing improvement to enable resource`() {
        // Add the needed tech to construct the improvements below
        for (improvement in listOf(RoadStatus.Road.name, "Farm", "Mine")) {
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        }
        civInfo.tech.techsResearched.add(testGame.ruleset.tileResources["Iron"]!!.revealedBy!!)

        testGame.addCity(civInfo, testGame.tileMap[0,0])

        val currentTile = testGame.tileMap[1,1] // owned by city
        currentTile.setImprovementBasic("Farm") // Set existing improvement
        currentTile.setTileResource("Iron") // This tile also has a resource needs to be enabled by a building a Mine

        val mapUnit = testGame.addUnit("Worker", civInfo, currentTile)

        // Act
        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(mapUnit))

        // Assert
        assertEquals("Worker should have replaced already existing improvement 'Farm' with 'Mine' to enable 'Iron' resource",
            "Mine", currentTile.improvementInProgress
        )
        assertTrue(currentTile.turnsToImprovement > 0)
    }

    @Test
    fun `should remove terrain feature enable resource`() {
        // Add the needed tech to construct the improvements below
        for (improvement in listOf("Remove Forest", "Plantation")) {
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        }

        testGame.addCity(civInfo, testGame.tileMap[0,0])

        val currentTile = testGame.tileMap[1,1]
        currentTile.addTerrainFeature("Hill")
        currentTile.addTerrainFeature("Forest")
        currentTile.setTileResource("Citrus")
        currentTile.resourceAmount = 1

        val mapUnit = testGame.addUnit("Worker", civInfo, currentTile)

        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(mapUnit))

        assertEquals("Worker should begun removing the forest to clear a luxury resource but didn't",
            "Remove Forest", currentTile.improvementInProgress
        )
        assertTrue(currentTile.turnsToImprovement > 0)
    }

    @Test
    fun `should build improvement on unseen resource`() {
        // Add the needed tech to construct the improvements below
        for (improvement in listOf(RoadStatus.Road.name, "Farm")) {
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        }

        val currentTile = testGame.tileMap[1,1]
        val city = testGame.addCity(civInfo, testGame.tileMap[0,0])
        // Currently worked tile is prioritized for worker actions
        city.clearWorkedTiles()
        city.workTile(currentTile)

        currentTile.baseTerrain = Constants.grassland
        currentTile.setTileResource("Iron")

        val mapUnit = testGame.addUnit("Worker", civInfo, currentTile)

        // Act
        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(mapUnit))

        // Assert
        assertEquals("Worker should be buliding a farm under 'Iron' resource because it can't see it and has nothing else to do",
            "Farm", currentTile.improvementInProgress
        )
        assertTrue(currentTile.turnsToImprovement > 0)
    }
    
    @Test
    fun `should prioritise better tiles`() {
        // Add the needed tech to construct the improvements below
        for (improvement in listOf(RoadStatus.Road.name, "Farm", "Mine")) {
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        }

        testGame.addCity(civInfo, testGame.tileMap[0,0])
        for (tile in testGame.tileMap[0,0].getTilesInDistance(3)) {
            tile.baseTerrain = Constants.grassland
        }

        val currentTile = testGame.tileMap[1,1]
        currentTile.baseTerrain = Constants.grassland
        currentTile.addTerrainFeature(Constants.hill)
        currentTile.setTileResource("Gold Ore")

        val mapUnit = testGame.addUnit("Worker", civInfo, currentTile)

        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(mapUnit))

        assertEquals("Worker should be buliding a mine on the Gold Ore luxury resource",
            "Mine", currentTile.improvementInProgress
        )
        assertTrue(currentTile.turnsToImprovement > 0)
    }

    @Test
    fun `should repair pillaged tile`() {
        // Add the needed tech to construct the improvements below
        for (improvement in listOf("Mine")) {
            civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements[improvement]!!.techRequired!!)
        }
        civInfo.tech.techsResearched.add(testGame.ruleset.tileResources["Iron"]!!.revealedBy!!)

        testGame.addCity(civInfo, testGame.tileMap[0,0])

        val currentTile = testGame.tileMap[1,1] // owned by city
        currentTile.setTileResource("Iron") // This tile also has a resource needs to be enabled by a building a Mine
        currentTile.setImprovement("Mine")
        currentTile.setPillaged()

        val mapUnit = testGame.addUnit("Worker", civInfo, currentTile)

        // Act
        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(mapUnit))

        // Assert
        assertEquals("Worker should try to repair the mine",
            "Repair", currentTile.improvementInProgress
        )
        assertTrue(currentTile.turnsToImprovement > 0)
    }


    @Test
    fun `automated workers should not target CreatesOneImprovement markers`() {
        civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements["Farm"]!!.techRequired!!)
        val city = testGame.addCity(civInfo, testGame.tileMap[0,0])

        val workerTile = city.getCenterTile()
        val markedTile = testGame.tileMap[1,1]
        markedTile.baseTerrain = Constants.grassland
        markedTile.improvementFunctions.markForCreatesOneImprovement("Farm")

        val worker = testGame.addUnit("Worker", civInfo, workerTile)
        worker.currentMovement = 2f

        workerAutomation.automateWorkerAction(GameView(civInfo.gameInfo, civInfo).getMapUnitView(worker))

        assertNotEquals(
            "Automated worker should not move onto a CreatesOneImprovement marker",
            markedTile,
            worker.getTile()
            )
        assertTrue(
            "CreatesOneImprovement marker should remain on the district tile",
            markedTile.isMarkedForCreatesOneImprovement()
            )
        assertEquals("Automated worker should leave CreatesOneImprovement marker unchanged",
            "Farm", markedTile.improvementInProgress)
        assertEquals("Automated worker should not convert CreatesOneImprovement marker into normal worker progress",
            -1, markedTile.turnsToImprovement)
    }

    @Test
    fun `worker orders should not overwrite CreatesOneImprovement markers`() {
        civInfo.tech.techsResearched.add(testGame.ruleset.tileImprovements["Farm"]!!.techRequired!!)
        testGame.addCity(civInfo, testGame.tileMap[0,0])

        val markedTile = testGame.tileMap[1,1]
        markedTile.baseTerrain = Constants.grassland
        markedTile.improvementFunctions.markForCreatesOneImprovement("Farm")

        val worker = testGame.addUnit("Worker", civInfo, markedTile)
        val farm = testGame.ruleset.tileImprovements["Farm"]!!

        markedTile.startWorkingOnImprovement(farm, civInfo, worker)

        assertEquals("Farm", markedTile.improvementInProgress)
        assertEquals(-1, markedTile.turnsToImprovement)
    }


}
