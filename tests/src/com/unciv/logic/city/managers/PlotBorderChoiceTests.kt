package com.unciv.logic.city.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvCity::GetBuyablePlotList and GetNextBuyablePlot with the GlobalDefines PLOT_INFLUENCE_DISTANCE_MULTIPLIER 100, ..._RING_COST 100,
 *  ..._WATER_COST 25, ..._IMPROVEMENT_COST -5, ..._RESOURCE_COST -105, ..._NW_COST -105, ..._YIELD_POINT_COST -1, ..._NO_ADJACENT_OWNED_COST 1000.
 *  On the open grassland of these tests every tile yields 2 food, so a tile of influence path cost n scores n x 100 - 2. */
@RunWith(GdxTestRunner::class)
class PlotBorderChoiceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord.Zero))
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(x, y)
    private val center get() = tile(0, 0)
    private val ringTwo get() = center.getTilesAtDistance(2).toSet()

    @Test
    fun `on open land every tile of the second ring is as good as another`() {
        assertEquals(ringTwo, city.expansion.getBuyablePlotList().toSet())
        assertEquals(12, city.expansion.getBuyablePlotList().size)
        assertEquals(198, city.expansion.plotInfluenceScore(tile(2, 0), 2))
    }

    @Test
    fun `tiles that touch none of the city's tiles score a thousand more`() {
        assertEquals(300 - 2 + 1000, city.expansion.plotInfluenceScore(tile(3, 0), 3))
    }

    @Test
    fun `a luxury is taken before a plain tile and tiles next to it are drawn towards it`() {
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury }
        tile(2, 0).setTileResource(luxury.name)
        assertEquals(200 - 105 - 2, city.expansion.plotInfluenceScore(tile(2, 0), 2))
        assertEquals(listOf(tile(2, 0)), city.expansion.getBuyablePlotList())
        assertEquals(tile(2, 0), city.expansion.chooseNewTileToOwn())
        // a neighbor of the luxury that is also in the second ring: -1
        assertEquals(198 - 1, city.expansion.plotInfluenceScore(tile(2, 1), 2))
    }

    @Test
    fun `a resource the civilization cannot see counts for nothing`() {
        val hidden = game.createResource().apply { resourceType = ResourceType.Luxury; revealedBy = "A technology nobody has" }
        tile(2, 0).setTileResource(hidden.name)
        assertEquals(198, city.expansion.plotInfluenceScore(tile(2, 0), 2))
        assertEquals(12, city.expansion.getBuyablePlotList().size)
    }

    @Test
    fun `a bonus resource is wanted within the work range and not beyond it`() {
        val bonus = game.createResource().apply { resourceType = ResourceType.Bonus }
        tile(3, 0).setTileResource(bonus.name)
        tile(0, 4).setTileResource(bonus.name)
        // distance 3: 300 - 105 + 1 - 2 + 1000;     distance 4: 400 + (no ring cost, no discount) - 2 + 1000
        assertEquals(300 - 104 - 2 + 1000, city.expansion.plotInfluenceScore(tile(3, 0), 3))
        assertEquals(400 - 2 + 1000, city.expansion.plotInfluenceScore(tile(0, 4), 4))
        // a bare tile of distance 4: + 100 for the ring
        assertEquals(400 + 100 - 2 + 1000, city.expansion.plotInfluenceScore(tile(-4, 0), 4))
    }

    @Test
    fun `water costs 25 more and a bare tile beyond three tiles 100 more`() {
        game.setTileTerrain(HexCoord(3, 0), "Coast")
        // coast: food 1 + no other yields: -1; water +25
        assertEquals(300 - 1 + 25 + 1000, city.expansion.plotInfluenceScore(tile(3, 0), 3))
        assertEquals(500 + 100 - 2 + 1000, city.expansion.plotInfluenceScore(tile(5, 0), 5))
    }

    @Test
    fun `an improvement lowers the score by 5`() {
        val farm = game.createTileImprovement()
        tile(2, 0).setImprovement(farm.name)
        assertEquals(198 - 5, city.expansion.plotInfluenceScore(tile(2, 0), 2))
    }

    @Test
    fun `a natural wonder beside a tile within the work range pulls the border`() {
        val wonder = tile(3, 0)
        wonder.naturalWonder = "El Dorado"
        wonder.setTerrainTransients()
        // (2,0) is the only tile of the second ring next to it: -1 for the wonder and -1 more since it is within the work range
        assertEquals(198 - 2, city.expansion.plotInfluenceScore(tile(2, 0), 2))
        assertEquals(listOf(tile(2, 0)), city.expansion.getBuyablePlotList())
    }

    @Test
    fun `a natural wonder itself scores 105 lower`() {
        val wonder = tile(3, 0)
        wonder.naturalWonder = "Barringer Crater"
        wonder.setTerrainTransients()
        val yields = wonder.stats.getTileStats(null, civ)
        val points = yields.food.toInt() + yields.production.toInt() + yields.gold.toInt() +
            yields.science.toInt() + yields.culture.toInt() + yields.faith.toInt()
        assertTrue(points > 2)
        assertEquals(300 - 105 - points + 1000, city.expansion.plotInfluenceScore(wonder, 3))
    }

    @Test
    fun `the cost of the path decides and hills in the second ring are taken last`() {
        for (tile in ringTwo) if (tile != tile(-2, 0)) game.setTileFeatures(tile.position, "Hill")
        // the path into a hill on grassland costs 1 + 1 + 1: 300, the hill yields 2 production
        assertEquals(300 - 2, city.expansion.plotInfluenceScore(tile(2, 0), 3))
        assertEquals(listOf(tile(-2, 0)), city.expansion.getBuyablePlotList())
        assertEquals(tile(-2, 0), city.expansion.chooseNewTileToOwn())
    }

    @Test
    fun `a river in the way raises the path cost and the tile is taken later`() {
        val flat = tile(-2, 0)
        for (tile in ringTwo) if (tile != flat) for (neighbor in tile.neighbors) tile.setConnectedByRiver(neighbor, true)
        // every ring-2 tile but one has a river on all of its edges: 1 + (1 + 1) = 3, and the flat one 2
        assertEquals(listOf(flat), city.expansion.getBuyablePlotList())
        assertEquals(3, PlotInfluence.distance(center, tile(2, 0), 5))
        assertEquals(2, PlotInfluence.distance(center, flat, 5))
    }

    @Test
    fun `a tile owned by anyone is not on the list`() {
        val foreignCity = game.addCity(game.addCiv(), tile(7, 0))
        val taken = tile(2, 0)
        foreignCity.expansion.takeOwnership(taken)
        assertFalse(taken in city.expansion.getBuyablePlotList())
        assertEquals(11, city.expansion.getBuyablePlotList().size)
    }

    @Test
    fun `the pick among equal tiles is random but the same until the city gains a tile`() {
        val list = city.expansion.getBuyablePlotList()
        val first = city.expansion.chooseNewTileToOwn()
        assertTrue(first in list)
        assertEquals(first, city.expansion.chooseNewTileToOwn())

        val picks = (0..40).map { level ->
            city.expansion.cultureLevel = level
            city.expansion.chooseNewTileToOwn()
        }.toSet()
        assertTrue("picked only ${picks.size} of 12 tiles", picks.size >= 6)
        assertTrue(ringTwo.containsAll(picks))
    }

    @Test
    fun `border growth takes the tile and works out the cheapest influence`() {
        for (tile in ringTwo) if (tile != tile(-2, 0)) game.setTileFeatures(tile.position, "Hill")
        city.expansion.cultureStored = 1000
        city.expansion.nextTurn(0f)
        assertEquals(city, tile(-2, 0).getCity())
        assertEquals(1, city.expansion.cultureLevel)
        assertEquals(0, civ.plotsBought)
        // ring-2 hills are left: 1 + (1 + 1) = 3; the tiles of the third ring cost more
        assertEquals(3, city.expansion.cheapestPlotInfluence)
    }

    @Test
    fun `extra territory at founding is chosen the same way`() {
        val settlers = game.addCiv("[2] additional tiles claimed when founding a city")
        val newCity = game.addCity(settlers, tile(-6, 0))
        assertEquals(7 + 2, newCity.tiles.size)
        assertEquals(2, newCity.expansion.cheapestPlotInfluence)
        assertEquals(2, newCity.tiles.count { tile(it.x, it.y).aerialDistanceTo(newCity.getCenterTile()) == 2 })
    }

    @Test
    fun `without the flag the old ranking is used`() {
        game.ruleset.modOptions.constants.plotAcquisitionLikeCivV = false
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury }
        tile(2, 0).setTileResource(luxury.name)
        assertEquals(tile(2, 0), city.expansion.chooseNewTileToOwn())
        assertEquals(city.expansion.getChoosableTiles().minBy { city.expansion.rankTileForExpansion(it) }, city.expansion.chooseNewTileToOwn())
    }
}
