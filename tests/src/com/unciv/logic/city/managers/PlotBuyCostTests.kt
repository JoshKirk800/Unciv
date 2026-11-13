package com.unciv.logic.city.managers

import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.city.City
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

/** CvPlayer::GetBuyPlotCost (PLOT_BASE_COST 50, PLOT_ADDITIONAL_COST_PER_PLOT 5, MINOR_CIV_GOLD_PERCENT 200), CvCity::GetBuyPlotCost
 *  (PLOT_INFLUENCE_BASE_MULTIPLIER 100, ..._DISTANCE_MULTIPLIER 100, ..._DISTANCE_DIVISOR 3, PLOT_BUY_RESOURCE_COST -100,
 *  PLOT_COST_APPEARANCE_DIVISOR 5), CvCity::CanBuyPlot (MAXIMUM_BUY_PLOT_DISTANCE 3), CvCity::DoUpdateCheapestPlotInfluence;
 *  all under the ModConstants plotAcquisitionLikeCivV of the Civ V rulesets */
@RunWith(GdxTestRunner::class)
class PlotBuyCostTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        city = game.addCity(civ, game.getTile(HexCoord.Zero))
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(x, y)
    private fun cost(x: Int, y: Int, extraTiles: Int = 0) = city.expansion.getGoldCostOfTile(tile(x, y), extraTiles)

    @Test
    fun `the player pays 50 plus 5 for each tile bought`() {
        assertEquals(50, civ.getBuyPlotCost())
        civ.plotsBought = 4
        assertEquals(70, civ.getBuyPlotCost())
        assertEquals(80, civ.getBuyPlotCost(extraPlots = 2))
    }

    @Test
    fun `a city-state pays double`() {
        val cityStateCiv = game.addCiv(cityStateType = "Militaristic")
        assertEquals(100, cityStateCiv.getBuyPlotCost())
        cityStateCiv.plotsBought = 3
        assertEquals(130, cityStateCiv.getBuyPlotCost()) // (50 + 15) x 200 / 100
    }

    @Test
    fun `the percentages of all cities are added and the product truncated`() {
        val frugal = game.addCiv("[-25]% Gold cost of acquiring tiles [in all cities]",
            "[-50]% Gold cost of acquiring tiles [in all cities]")
        assertEquals(12, frugal.getBuyPlotCost()) // 50 x (100 - 75) / 100, not 50 x 0.75 x 0.5 = 18
    }

    @Test
    fun `a fresh city has no cheapest influence yet so the distance counts in full`() {
        assertEquals(0, city.expansion.cheapestPlotInfluence)
        // influence distance 2: factor 100 + 2 x 100 / 3 = 166, 50 x 166 / 100 = 83, rounded down to 5
        assertEquals(80, cost(2, 0))
    }

    @Test
    fun `the distance counts beyond the cheapest unowned tile`() {
        city.expansion.cheapestPlotInfluence = 2
        assertEquals(50, cost(2, 0)) // 2 - 2 = 0: factor 100, no scaling
        assertEquals(65, cost(3, 0)) // 3 - 2 = 1: 133, 50 x 133 / 100 = 66
    }

    @Test
    fun `the price is rounded down to five after every other step`() {
        city.expansion.cheapestPlotInfluence = 2
        civ.plotsBought = 4 // 70
        assertEquals(70, cost(2, 0))
        assertEquals(90, cost(3, 0)) // 70 x 133 / 100 = 93
        civ.plotsBought = 9 // 95
        assertEquals(125, cost(3, 0)) // 95 x 133 / 100 = 126
    }

    @Test
    fun `a resource the civilization can see takes 100 points off the factor`() {
        city.expansion.cheapestPlotInfluence = 1
        val resource = game.createResource()
        tile(3, 0).setTileResource(resource.name)
        assertEquals(50, cost(3, 0)) // 166 - 100 = 66, not above 100: no scaling
        assertEquals(65, cost(2, 0)) // control, no resource: 133, 66 -> 65

        val hidden = game.createResource()
        hidden.revealedBy = "A technology nobody has"
        tile(2, 0).setTileResource(hidden.name)
        assertEquals(65, cost(2, 0)) // not revealed: full price
    }

    @Test
    fun `a far resource tile is cheap, a flat one with the same distance is not`() {
        city.expansion.cheapestPlotInfluence = 1
        civ.plotsBought = 10 // 100
        val resource = game.createResource()
        tile(3, 0).setTileResource(resource.name)
        assertEquals(100, cost(3, 0)) // 166 - 100 = 66: no scaling
        assertEquals(165, cost(0, 3)) // 100 x 166 / 100
    }

    @Test
    fun `the game speed scales the price after the distance`() {
        game.setSpeed("Epic")
        city.expansion.cheapestPlotInfluence = 1
        assertEquals(95, cost(2, 0)) // 66 x 150 / 100 = 99
    }

    @Test
    fun `the percentages of the city's own uniques come last`() {
        city.expansion.cheapestPlotInfluence = 1
        city.cityConstructions.addBuilding(game.createBuilding("[-50]% Gold cost of acquiring tiles [in this city]"))
        assertEquals(30, cost(2, 0)) // 66 x 50 / 100 = 33
    }

    @Test
    fun `buying several tiles raises the price of each one`() {
        city.expansion.cheapestPlotInfluence = 2
        assertEquals(50, cost(2, 0))
        assertEquals(55, cost(2, 0, extraTiles = 1))
        assertEquals(60, cost(2, 0, extraTiles = 2))
    }

    @Test
    fun `a tile beyond three tiles costs 9999 and cannot be bought`() {
        for (position in listOf(HexCoord(2, 0), HexCoord(3, 0))) city.expansion.takeOwnership(game.getTile(position))
        assertTrue(city.expansion.canBuyTile(tile(3, 1)))   // distance 3, next to (2,0) which the city owns
        assertFalse(city.expansion.canBuyTile(tile(4, 0)))  // distance 4, next to (3,0)
        assertEquals(9999, cost(4, 0))

        // Unciv's own rule: the expand range
        game.ruleset.modOptions.constants.plotAcquisitionLikeCivV = false
        assertTrue(city.expansion.canBuyTile(tile(4, 0)))
    }

    @Test
    fun `without the flag the price is Unciv's own`() {
        game.ruleset.modOptions.constants.plotAcquisitionLikeCivV = false
        civ.plotsBought = 50 // not used
        assertEquals(50, cost(2, 0)) // 50 x (2 - 1) + 5 x 0 tiles claimed outside the first ring
        assertEquals(100, cost(3, 0))
        assertEquals(55, cost(2, 0, extraTiles = 1))
    }

    @Test
    fun `buying a tile pays its price, counts it for the civilization and works out the cheapest influence`() {
        civ.addGold(1000)
        val price = cost(2, 0)
        city.expansion.buyTile(tile(2, 0))
        assertEquals(1000 - price, civ.gold)
        assertEquals(1, civ.plotsBought)
        assertEquals(tile(2, 0).getCity(), city)
        assertEquals(55, civ.getBuyPlotCost())
        // the unowned ring-2 tiles that are left cost 1 + 1
        assertEquals(2, city.expansion.cheapestPlotInfluence)
    }

    @Test
    fun `the counter is the civilization's, every city pays for the tiles of the others`() {
        val second = game.addCity(civ, tile(6, 0))
        civ.addGold(1000)
        city.expansion.buyTile(tile(2, 0))
        city.expansion.buyTile(tile(-2, 0))
        assertEquals(2, civ.plotsBought)
        assertEquals(60, civ.getBuyPlotCost())
        second.expansion.cheapestPlotInfluence = 2
        assertEquals(60, second.expansion.getGoldCostOfTile(tile(4, 0)))
    }

    @Test
    fun `taking a tile with culture does not count as bought`() {
        city.expansion.acquireTile(tile(2, 0))
        assertEquals(0, civ.plotsBought)
    }

    @Test
    fun `the cheapest influence is the cost of the cheapest unowned tile`() {
        for (tile in game.tileMap.values) if (tile.aerialDistanceTo(tile(0, 0)) > 1) game.setTileFeatures(tile.position, "Hill")
        city.expansion.acquireTile(tile(2, 0))
        // first step 1, then a hill on grassland 1 + 1
        assertEquals(3, city.expansion.cheapestPlotInfluence)
    }

    @Test
    fun `a saved game without the value gets it worked out`() {
        city.expansion.cheapestPlotInfluence = -1
        assertEquals(2, city.expansion.effectiveCheapestPlotInfluence())
        assertEquals(50, cost(2, 0))
    }

    @Test
    fun `the AI prices a tile as the civilization does and buys only what it can pay`() {
        civ.plotsBought = 6
        assertEquals(80, UseGoldAutomation.baseTileCost(city))
        civ.addGold(10)
        assertNull(UseGoldAutomation.buyPlotScore(city))
        civ.addGold(1000)
        assertNotNull(UseGoldAutomation.buyPlotScore(city))
    }

    @Test
    fun `cloning a civilization keeps the number of tiles bought`() {
        civ.plotsBought = 7
        assertEquals(7, civ.clone().plotsBought)
    }
}
