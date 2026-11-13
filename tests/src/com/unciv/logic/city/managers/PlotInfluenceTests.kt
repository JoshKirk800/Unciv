package com.unciv.logic.city.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvMap::calculateInfluenceDistance with CvAStar.cpp InfluenceCost: the first step 1, then the terrain, features, hill, mountain
 *  and river costs of the tile entered held within 1..3, +15 into a tile of another civilization; the costs are the XML `InfluenceCost`
 *  of the terrains and features and the GlobalDefines INFLUENCE_HILL_COST (BNW 1, G&K 2), INFLUENCE_MOUNTAIN_COST 3, INFLUENCE_RIVER_COST 1 */
@RunWith(GdxTestRunner::class)
class PlotInfluenceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var center: Tile

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        center = game.getTile(HexCoord.Zero)
    }

    /** Every tile that is not within the first ring gets [terrain] and [features]; then the city is founded */
    private fun landscape(terrain: String, vararg features: String) {
        for (tile in game.tileMap.values) {
            if (tile.aerialDistanceTo(center) <= 1) game.setTileTerrain(tile.position, "Grassland")
            else game.setTileTerrainAndFeatures(tile.position, terrain, *features)
        }
        game.addCity(civ, center)
    }

    private fun distance(x: Int, y: Int, range: Int = 5) = PlotInfluence.distance(center, game.getTile(x, y), range)

    @Test
    fun `the first step costs one and every step on grassland one`() {
        landscape("Grassland")
        assertEquals(1, distance(1, 0))
        assertEquals(2, distance(2, 0))
        assertEquals(5, distance(5, 0))
    }

    @Test
    fun `terrain costs are those of the XML`() {
        // 1 (first step) + (distance - 1) x cost of the terrain of the other tiles
        for ((terrain, cost) in listOf("Plains" to 1, "Desert" to 2, "Tundra" to 2, "Snow" to 2, "Coast" to 2, "Ocean" to 3)) {
            landscape(terrain)
            assertEquals(terrain, 1 + 2 * cost, distance(3, 0))
            setUp()
        }
    }

    @Test
    fun `terrain and feature costs add up and a step costs at most three`() {
        landscape("Grassland", "Forest") // 1 + 1
        assertEquals(1 + 2 * 2, distance(3, 0))
        setUp()
        landscape("Grassland", "Hill", "Forest") // 1 + 1 + 1 = 3
        assertEquals(1 + 2 * 3, distance(3, 0))
        setUp()
        landscape("Ocean", "Ice") // 3 + 3 = 6 held to 3
        assertEquals(1 + 2 * 3, distance(3, 0))
        setUp()
        landscape("Desert", "Oasis") // 2 - 1
        assertEquals(1 + 2 * 1, distance(3, 0))
        setUp()
        landscape("Desert", "Flood plains") // 2 - 1
        assertEquals(1 + 2 * 1, distance(3, 0))
    }

    @Test
    fun `a hill costs INFLUENCE_HILL_COST on top of its terrain`() {
        landscape("Grassland", "Hill") // BNW: 1 + 1
        assertEquals(1 + 2 * 2, distance(3, 0))
        assertEquals(1, game.ruleset.terrains["Hill"]!!.influenceCost)
    }

    @Test
    fun `the hill of Gods and Kings costs 2`() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_GnK)
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        center = game.getTile(HexCoord.Zero)
        landscape("Grassland", "Hill") // G&K: 2 + 1 = 3
        assertEquals(1 + 2 * 3, distance(3, 0))
    }

    @Test
    fun `a mountain costs three whatever is on it`() {
        landscape("Grassland")
        val destination = game.setTileTerrain(HexCoord(3, 0), "Mountain")
        // 1 + 1 + 3, the same with a natural wonder on the mountain whose -3 is not counted
        assertEquals(5, distance(3, 0))
        destination.naturalWonder = "Mount Fuji"
        destination.setTerrainTransients()
        assertEquals(5, distance(3, 0))
    }

    @Test
    fun `a natural wonder on other land lowers the cost of its tile`() {
        landscape("Desert")
        val destination = game.getTile(3, 0)
        assertEquals(1 + 2 + 2, distance(3, 0))
        destination.naturalWonder = "El Dorado"
        destination.setTerrainTransients() // desert 2 - 3, held to 1
        assertEquals(1 + 2 + 1, distance(3, 0))
    }

    @Test
    fun `crossing a river costs one more but not on the first step`() {
        landscape("Grassland")
        val destination = game.getTile(2, 0)
        for (neighbor in destination.neighbors) destination.setConnectedByRiver(neighbor, true)
        assertEquals(1 + 2, distance(2, 0)) // 1 + (1 river + 1 grassland)

        val ringOne = game.getTile(1, 0)
        center.setConnectedByRiver(ringOne, true)
        assertEquals(1, distance(1, 0))
    }

    @Test
    fun `a tile of another civilization costs fifteen more on every step after the first`() {
        landscape("Grassland")
        assertEquals(3, distance(3, 0))
        val foreign = game.addCiv()
        val foreignCity = game.addCity(foreign, game.getTile(7, 0))
        for (tile in center.getTilesAtDistance(2)) foreignCity.expansion.takeOwnership(tile)
        // 1 (first step) + (1 + 15) (a tile of the foreign ring) + 1
        assertEquals(1 + 16 + 1, distance(3, 0))
        // the foreign ring itself: the first step is free of the surcharge, the second pays it
        assertEquals(1 + 16, PlotInfluence.distance(center, game.getTile(2, 0), 5))
    }

    @Test
    fun `a destination beyond the range has no distance`() {
        landscape("Grassland")
        assertEquals(-1, distance(4, 0, range = 3))
        assertEquals(4, distance(4, 0, range = 4))
    }

    @Test
    fun `a ring of mountains is paid once`() {
        landscape("Grassland")
        // a wall of mountains one tile thick around the city at ring 2: every path through it pays 3 for it
        for (tile in center.getTilesAtDistance(2)) game.setTileTerrain(tile.position, "Mountain")
        assertEquals(1 + 3 + 1, distance(3, 0))
    }

    @Test
    fun `the Civ V rulesets carry the costs of the XML`() {
        val terrains = game.ruleset.terrains
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 3, 3), listOf("Grassland", "Plains", "Desert", "Tundra", "Snow", "Coast", "Ocean", "Mountain")
            .map { terrains[it]!!.influenceCost })
        assertEquals(listOf(1, 1, 1, -1, -1, 3, -1), listOf("Forest", "Jungle", "Marsh", "Oasis", "Flood plains", "Ice", "Atoll")
            .map { terrains[it]!!.influenceCost })
        assertEquals(17, terrains.values.count { it.type == TerrainType.NaturalWonder && it.influenceCost == -3 })
    }
}
