package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvCity::isCoastal(minWaterSize) in the strategy tests: land next to a body of water of at least MIN_WATER_SIZE_FOR_OCEAN (10) tiles
 *  (LANDLOCKED, COAST_CITY, ONE_OR_FEWER_COASTAL_CITIES), at least 100 for MOSTLY_ON_THE_COAST and the city specialization's coastal pick.
 *  A sea of Coast tiles around a city on grassland, [seaSize] tiles around (2, 0) not counting the city.
 */
@RunWith(GdxTestRunner::class)
class CoastalStrategyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12, "Grassland")
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
    }

    private fun tile(x: Int, y: Int): Tile = game.tileMap[HexCoord(x, y)]

    /** Turns [seaSize] tiles nearest to (2, 0), but never [keep], into one body of water; a new turn so that the water bodies are computed again */
    private fun makeSea(seaSize: Int, keep: Tile) {
        val center = tile(2, 0)
        val sea = game.tileMap.values.filter { it != keep }.sortedBy { it.aerialDistanceTo(center) }.take(seaSize)
        for (water in sea) game.setTileTerrain(water.position, "Coast")
        game.gameInfo.turns++
    }

    private fun capital(): City = game.addCity(civ, tile(0, 0), initialPopulation = 3)

    @Test
    fun `a city is coastal on a body of water of 10 tiles, not 9`() {
        val city = capital()
        makeSea(9, city.getCenterTile())
        assertFalse(WaterBodies.isCoastal(city))
        makeSea(10, city.getCenterTile())
        assertTrue(WaterBodies.isCoastal(city))
        assertFalse(WaterBodies.isCoastal(city, 100))
    }

    @Test
    fun `a city on a 9-tile pond is landlocked, not a coast city`() {
        val city = capital()
        makeSea(9, city.getCenterTile())
        city.ai.doTurn()
        assertTrue(city.ai.isCityStrategyActive("AICITYSTRATEGY_LANDLOCKED"))
        assertFalse(city.ai.isCityStrategyActive("AICITYSTRATEGY_COAST_CITY"))
    }

    @Test
    fun `a city on a 10-tile sea is a coast city, not landlocked`() {
        val city = capital()
        makeSea(10, city.getCenterTile())
        city.ai.doTurn()
        assertFalse(city.ai.isCityStrategyActive("AICITYSTRATEGY_LANDLOCKED"))
        assertTrue(city.ai.isCityStrategyActive("AICITYSTRATEGY_COAST_CITY"))
    }

    @Test
    fun `one or fewer coastal cities counts cities on seas of 10 tiles`() {
        val city = capital()
        game.addCity(civ, tile(8, 4), initialPopulation = 3)
        makeSea(9, city.getCenterTile())
        assertEquals(0, civ.cities.count { WaterBodies.isCoastal(it) })
        makeSea(10, city.getCenterTile())
        assertEquals(1, civ.cities.count { WaterBodies.isCoastal(it) })
        civ.ai.doTurn()
        assertTrue(civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ONE_OR_FEWER_COASTAL_CITIES"))
    }

    @Test
    fun `mostly on the coast needs a sea of 100 tiles, not a lake or an inland sea`() {
        val city = capital()
        makeSea(99, city.getCenterTile())
        assertFalse(EconomicMilitaryStrategyTests.mostlyOnTheCoast(civ))
        makeSea(100, city.getCenterTile())
        assertTrue(EconomicMilitaryStrategyTests.mostlyOnTheCoast(civ))
    }
}
