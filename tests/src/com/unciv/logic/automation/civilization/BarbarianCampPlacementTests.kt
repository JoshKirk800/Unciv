package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

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

    /**
     *  A long strip of land (the tiles with x = 0) and a block of land far from it that a city-state owns, so no camp goes there and the
     *  strip is the only place for one; both lie in the fog and count for the number of camps the map is to have, the strip for a small
     *  share of the land. Returns the strip.
     */
    private fun setUpStripAndOwnedBlock(): List<Tile> {
        game.makeHexagonalMap(25)
        game.addBarbarianCiv()
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.ocean)
        val strip = (-25..25).map { game.setTileTerrain(HexCoord(0, it), Constants.grassland) }
        val blockCenter = game.tileMap.values.first { c -> c.aerialDistanceTo(game.getTile(0, 0)) in 16..17 && strip.all { it.aerialDistanceTo(c) >= 11 } }
        val block = game.tileMap.values.filter { it.aerialDistanceTo(blockCenter) <= 8 }
        for (tile in block) game.setTileTerrain(tile.position, Constants.grassland)
        val cityState = game.addCiv(cityStateType = "Cultured")
        val city = game.addCity(cityState, blockCenter)
        for (tile in block) if (tile != blockCenter) tile.setOwningCity(city)
        game.gameInfo.barbarians.setTransients(game.gameInfo)
        return strip
    }

    /** The most camps a landmass of [landmassTiles] tiles may already hold for another to be placed on it (CvBarbarians::DoCamps) */
    private fun campsAllowedBeforeAnother(landmassTiles: Int): Int {
        val land = game.tileMap.values.filter { it.isLand }
        val fog = land.count { tile -> game.gameInfo.civilizations.none { tile in it.viewableTiles } }
        val fogTilesPerCamp = game.tileMap.values.size.toFloat().pow(0.4f).toInt()
        return fog / fogTilesPerCamp * landmassTiles / land.size + 1
    }

    private fun campsOnStrip(strip: List<Tile>) = strip.count { it.isBarbarianEncampment() }

    private fun placeCamps(strip: List<Tile>, count: Int) {
        // Tiles 8 apart: not too close to one another
        for (i in 0 until count) game.gameInfo.barbarians.createNewCamp(strip[i * 8])
    }

    @Test
    fun `a landmass that has more camps than its share of the target, plus one, gets no more`() {
        val strip = setUpStripAndOwnedBlock()
        val allowed = campsAllowedBeforeAnother(strip.size)
        assertTrue("the strip is long enough for more camps than the cap lets it have: $allowed", allowed + 1 < strip.size / 8)
        placeCamps(strip, allowed + 1)
        game.gameInfo.barbarians.placeBarbarianEncampment(forTesting = true)
        assertEquals(allowed + 1, campsOnStrip(strip))
    }

    @Test
    fun `a landmass that has as many camps as its share of the target, plus one, still gets one`() {
        val strip = setUpStripAndOwnedBlock()
        val allowed = campsAllowedBeforeAnother(strip.size)
        placeCamps(strip, allowed)
        game.gameInfo.barbarians.placeBarbarianEncampment(forTesting = true)
        assertEquals(allowed + 1, campsOnStrip(strip))
    }
}
