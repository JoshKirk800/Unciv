package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The natural wonders hide and are hidden like the "see through" value of their feature in the original (CvPlot::seeThroughLevel, Features XML: 2),
 *  which Unciv models with "Blocks line-of-sight as if [2] elevation higher" on top of the terrain the wonder turns into */
@RunWith(BaseTestRunner::class)
class NaturalWonderSightTests {

    private fun TestGame.placeWonder(position: HexCoord, wonder: String): com.unciv.logic.map.tile.Tile {
        val tile = tileMap[position]
        tile.naturalWonder = wonder
        tile.baseTerrain = ruleset.terrains[wonder]!!.turnsInto!!
        tile.setTerrainTransients()
        return tile
    }

    private fun flatLand(game: TestGame) {
        for (x in 0..3) game.setTileTerrain(HexCoord(x, 0), Constants.grassland)
    }

    @Test
    fun `El Dorado, a flat wonder, hides a hill behind it`() {
        val game = TestGame()
        game.makeHexagonalMap(5)
        flatLand(game)
        val source = game.tileMap[HexCoord.Zero]
        game.placeWonder(HexCoord(1, 0), "El Dorado")
        val hill = game.setTileTerrainAndFeatures(HexCoord(2, 0), Constants.grassland, Constants.hill)

        assertFalse(source.getViewableTilesList(2).contains(hill))
    }

    @Test
    fun `Plains do not hide a hill behind them`() {
        val game = TestGame()
        game.makeHexagonalMap(5)
        flatLand(game)
        val source = game.tileMap[HexCoord.Zero]
        game.setTileTerrain(HexCoord(1, 0), Constants.plains)
        val hill = game.setTileTerrainAndFeatures(HexCoord(2, 0), Constants.grassland, Constants.hill)

        assertTrue(source.getViewableTilesList(2).contains(hill))
    }

    @Test
    fun `King Solomon's Mines and the Fountain of Youth are seen over a hill, a flat tile is not`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        flatLand(game)
        val source = game.tileMap[HexCoord.Zero]
        game.setTileTerrainAndFeatures(HexCoord(1, 0), Constants.grassland, Constants.hill)
        val mines = game.placeWonder(HexCoord(2, 0), "King Solomon's Mines")
        game.setTileTerrainAndFeatures(HexCoord(-1, 0), Constants.grassland, Constants.hill)
        val flat = game.setTileTerrain(HexCoord(-2, 0), Constants.plains)
        game.setTileTerrainAndFeatures(HexCoord(0, 1), Constants.grassland, Constants.hill)
        val fountain = game.placeWonder(HexCoord(0, 2), "Fountain of Youth")

        val viewable = source.getViewableTilesList(2)
        assertTrue(viewable.contains(mines))
        assertFalse(viewable.contains(flat))
        assertTrue(viewable.contains(fountain))
    }

    @Test
    fun `A mountain wonder is seen over a mountain, a plain mountain is not`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        flatLand(game)
        val source = game.tileMap[HexCoord.Zero]
        game.setTileTerrain(HexCoord(1, 0), Constants.mountain)
        val fuji = game.placeWonder(HexCoord(2, 0), "Mount Fuji")
        val mountain = game.setTileTerrain(HexCoord(0, 2), Constants.mountain)
        game.setTileTerrain(HexCoord(0, 1), Constants.mountain)

        val viewable = source.getViewableTilesList(2)
        assertTrue(viewable.contains(fuji))
        assertFalse(viewable.contains(mountain))
    }

    @Test
    fun `The wonder's height for line of sight is the original's see-through level above flat land or the mountain it turns into`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        // elevation of the terrain it turns into (0 flat, 2 mountain) + the 2 of the feature's see-through level
        assertEquals(2, game.placeWonder(HexCoord(1, 0), "El Dorado").tileHeight)
        assertEquals(0, game.tileMap[HexCoord(1, 0)].unitHeight)
        assertEquals(4, game.placeWonder(HexCoord(2, 0), "Krakatoa").tileHeight)
        assertEquals(4, game.placeWonder(HexCoord(3, 0), "Mount Kilimanjaro").tileHeight)
        // Rock of Gibraltar: flat land with level 2 in the original, which the Mountain it turns into gives
        assertEquals(2, game.placeWonder(HexCoord(0, 1), "Rock of Gibraltar").tileHeight)
    }

    @Test
    fun `The Great Barrier Reef has a see-through level in Gods and Kings only`() {
        val gk = TestGame()
        gk.makeHexagonalMap(2)
        assertEquals(2, gk.placeWonder(HexCoord(1, 0), "Great Barrier Reef").tileHeight)

        val bnw = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        bnw.makeHexagonalMap(2)
        assertEquals(0, bnw.placeWonder(HexCoord(1, 0), "Great Barrier Reef").tileHeight)
    }
}
