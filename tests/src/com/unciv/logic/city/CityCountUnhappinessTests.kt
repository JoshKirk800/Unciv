package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapSize
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Unhappiness from the number of cities is scaled by the map size: 100% up to Medium (the original's Standard), 80% Large, 60% Huge. */
@RunWith(BaseTestRunner::class)
class CityCountUnhappinessTests {
    private val game = TestGame()

    private fun citiesUnhappiness(size: MapSize.Predefined, annexed: Boolean = false): Float {
        game.makeHexagonalMap(2)
        game.tileMap.mapParameters.mapSize = MapSize(size)
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), true)
        if (annexed) city.foundingCivObject = game.addCiv() // taken from another civilization, not a puppet
        city.cityStats.update()
        return city.cityStats.happinessList["Cities"]!!
    }

    @Test
    fun `the unhappiness per city shrinks on larger maps`() {
        val standard = citiesUnhappiness(MapSize.Predefined.Medium)
        assertEquals("Tiny", standard, citiesUnhappiness(MapSize.Predefined.Tiny), 0.001f)
        assertEquals("Small", standard, citiesUnhappiness(MapSize.Predefined.Small), 0.001f)
        assertEquals("Large", standard * 0.8f, citiesUnhappiness(MapSize.Predefined.Large), 0.001f)
        assertEquals("Huge", standard * 0.6f, citiesUnhappiness(MapSize.Predefined.Huge), 0.001f)
    }

    @Test
    fun `an annexed city is scaled the same way`() {
        val standard = citiesUnhappiness(MapSize.Predefined.Medium, annexed = true)
        assertTrue("annexed costs more: $standard", standard < citiesUnhappiness(MapSize.Predefined.Medium))
        assertEquals(standard * 0.6f, citiesUnhappiness(MapSize.Predefined.Huge, annexed = true), 0.001f)
    }
}
