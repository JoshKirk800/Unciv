package com.unciv.uniques

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Old Faithful (+3) and the Fountain of Youth (+10) give their happiness for every tile of theirs inside the borders, worked or not
 *  (CvPlayer::GetHappinessFromNaturalWonders). Uses the wonders as defined in the G&K ruleset. */
@RunWith(BaseTestRunner::class)
class StatsWhileInBordersTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
    }

    private fun happiness(): Float {
        city.workedTiles.clear() // the point is that the wonder tile is not worked
        city.cityStats.update()
        return civ.stats.getStatMapForNextTurn().values.sumOf { it.happiness.toDouble() }.toFloat()
    }

    private fun place(wonder: String, x: Int, y: Int): Tile {
        val tile = game.getTile(x, y)
        tile.naturalWonder = wonder
        tile.setTerrainTransients()
        return tile
    }

    @Test
    fun `an owned wonder tile gives its happiness without being worked`() {
        val before = happiness()
        val tile = place("Old Faithful", 1, 0)
        game.addTileToCity(city, tile)

        assertEquals(before + 3f, happiness(), 0.001f)
        assertFalse("not worked", city.isWorked(tile))

        val fountain = place("Fountain of Youth", -1, 0)
        game.addTileToCity(city, fountain)
        assertEquals(before + 13f, happiness(), 0.001f)
    }

    @Test
    fun `a wonder tile outside the borders gives nothing`() {
        val before = happiness()
        place("Old Faithful", 4, 0)
        place("Fountain of Youth", -4, 0)

        assertEquals(before, happiness(), 0.001f)
    }

    @Test
    fun `each owned tile counts`() {
        val before = happiness()
        game.addTileToCity(city, place("Old Faithful", 1, 0))
        game.addTileToCity(city, place("Old Faithful", 0, 1))

        assertEquals(before + 6f, happiness(), 0.001f)
    }
}
