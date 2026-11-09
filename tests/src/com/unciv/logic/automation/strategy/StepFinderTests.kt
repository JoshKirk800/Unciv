package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  [StepFinder.isValid] against the original's StepValid (CvAStar.cpp): a land route through foreign territory is blocked only where the
 *  owner is a major civilization that is not the enemy of the operation, not at war with us and not friendly territory for us; city-state
 *  land is always valid. One strip of land (y = 0) between water: our city at x=-8, the enemy's at x=8, something owned in the middle.
 */
@RunWith(GdxTestRunner::class)
class StepFinderTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var from: Tile
    private lateinit var to: Tile

    @Before
    fun setUp() {
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) if (tile.position.y != 0) game.setTileTerrain(tile.position, "Coast")
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        from = game.addCity(civ, tile(-8)).getCenterTile()
        to = game.addCity(enemy, tile(8)).getCenterTile()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int) = game.getTile(HexCoord(x, 0))

    private fun steps(forEnemy: Civilization? = enemy) = StepFinder().distance(civ, forEnemy, from, to)

    @Test
    fun `the strip is walkable when nobody owns the middle`() = assertEquals(16, steps())

    @Test
    fun `a city state's land can be crossed by an AI civilization without open borders`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(0))
        assertEquals(16, steps())
    }

    @Test
    fun `a major's land that is not the enemy's blocks the route unless we are at war with it`() {
        val third = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(third)
        game.addCity(third, tile(0))
        assertNull(StepFinder().path(civ, enemy, from, to))
        civ.getDiplomacyManager(third)!!.declareWar()
        assertNotNull(StepFinder().path(civ, enemy, from, to))
    }

    @Test
    fun `the land of the enemy of the operation is valid even without war`() {
        game.addCity(enemy, tile(0))
        assertEquals(16, steps())
        assertEquals("not when it is not the enemy of the operation and at peace", -1, steps(forEnemy = null))
    }

    /** A sea route from one side of the strip to the other, between two water tiles, across the wall of water at x = 0 */
    private fun seaRoute(wall: String): Pair<Tile, Tile> {
        for (tile in game.tileMap.values) if (tile.position.x == 0 && tile.position.y != 0) game.setTileTerrain(tile.position, wall)
        return game.getTile(HexCoord(-8, 1)) to game.getTile(HexCoord(8, 1))
    }

    @Test
    fun `a route over water goes through coast`() {
        val (start, end) = seaRoute("Coast")
        assertNotNull(StepFinder().path(civ, enemy, start, end, sea = true))
    }

    @Test
    fun `deep ocean is impassable for a route over water until the civilization can embark onto it`() {
        val (start, end) = seaRoute("Ocean")
        assertNull(StepFinder().path(civ, enemy, start, end, sea = true))
        assertFalse(StepFinder().seaPathExists(civ, enemy, start, end))
        civ.tech.embarkedUnitsCanEnterOcean = true
        assertNotNull(StepFinder().path(civ, enemy, start, end, sea = true))
        assertTrue(StepFinder().seaPathExists(civ, enemy, start, end))
    }
}
