package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapgenerator.mapregions.CivVStartAssignment
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** The types of region (DetermineRegionTypes) and which civ gets which (BalanceAndAssign) */
@RunWith(GdxTestRunner::class)
class CivVRegionTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    /** One region that is the whole map of [size] x [size] grassland tiles, [decorate] changes some of them */
    private fun regionType(size: Int = 20, decorate: (List<com.unciv.logic.map.tile.Tile>) -> Unit): String {
        game.makeRectangularMap(newHeight = size, newWidth = size, baseTerrain = "Grassland")
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        val tiles = game.tileMap.values.toList().sortedBy { it.position.x * 1000 + it.position.y }
        decorate(tiles)
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        val civ = game.addCiv()
        val regions = MapRegions(game.ruleset)
        regions.generateRegions(game.tileMap, 1)
        regions.assignRegions(game.tileMap, listOf(civ), GameParameters())
        return regions.regions.single().type
    }

    private fun hills(share: Double) = { tiles: List<com.unciv.logic.map.tile.Tile> ->
        for (tile in tiles.take((tiles.size * share).toInt())) game.setTileFeatures(tile.position, "Hill")
    }

    @Test
    fun `a region of hills needs 41,5 percent of its tiles to be hills`() {
        assertNotEquals("Hill", regionType(decorate = hills(0.41)))
        assertEquals("Hill", regionType(decorate = hills(0.42)))
    }

    private fun jungleAndForest(jungle: Double, forest: Double) = { tiles: List<com.unciv.logic.map.tile.Tile> ->
        val j = (tiles.size * jungle).toInt()
        val f = (tiles.size * forest).toInt()
        for (tile in tiles.take(j)) game.setTileTerrain(tile.position, "Plains").also { game.setTileFeatures(tile.position, "Jungle") }
        for (tile in tiles.drop(j).take(f)) game.setTileFeatures(tile.position, "Forest")
    }

    @Test
    fun `jungle and forest together make a region of either only with a fifth of it of its own kind, the jungle first`() {
        // 25 % jungle and 12 % forest: 37 % together, and a fifth of jungle
        assertEquals("Jungle", regionType(decorate = jungleAndForest(0.25, 0.12)))
        // 15 % jungle and 22 % forest: the jungle lacks its fifth, the forest has it
        assertEquals("Forest", regionType(decorate = jungleAndForest(0.15, 0.22)))
        // 15 % and 15 %: 30 % together is not enough, and neither has 30 % alone
        assertNotEquals("Jungle", regionType(decorate = jungleAndForest(0.15, 0.15)))
        assertNotEquals("Forest", regionType(decorate = jungleAndForest(0.15, 0.15)))
        // More forest than jungle but a fifth of jungle: the jungle is tried first and wins (the Unciv data used to turn it away)
        assertEquals("Jungle", regionType(decorate = jungleAndForest(0.22, 0.30)))
    }

    private fun plainsAndGrass(plains: Double) = { tiles: List<com.unciv.logic.map.tile.Tile> ->
        for (tile in tiles.take((tiles.size * plains).toInt())) game.setTileTerrain(tile.position, "Plains")
    }

    @Test
    fun `plains region needs the grass to be under 70 percent of the plains`() {
        // 40 % plains, 60 % grass: grass is not under 70 % of plains, and plains is under 70 % of grass: a grass region
        assertEquals("Grassland", regionType(decorate = plainsAndGrass(0.4)))
        // 80 % plains
        assertEquals("Plains", regionType(decorate = plainsAndGrass(0.8)))
        // 50 % each: neither is under 70 % of the other: nothing
        assertEquals("Hybrid", regionType(decorate = plainsAndGrass(0.5)))
    }

    // region: who gets which region

    private fun landMapWithSea(): List<Region> {
        game.makeRectangularMap(newHeight = 12, newWidth = 40, baseTerrain = "Grassland")
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        val tiles = game.tileMap.values.toList().sortedBy { it.getColumn() }
        // Three regions of 13 columns each; the first two have a coast next to their start, the third has not
        val first = tiles.first().getColumn()
        fun startIn(column: Int) = tiles.filter { it.getColumn() == column }.minBy { kotlin.math.abs(it.getRow() - tiles.filter { t -> t.getColumn() == column }.map { t -> t.getRow() }.average()) }
        val starts = listOf(startIn(first + 6), startIn(first + 20), startIn(first + 34))
        for (n in starts.take(2).map { it.neighbors.first() }) game.setTileTerrain(n.position, "Coast")
        game.tileMap.setTransients(game.ruleset)
        return starts.mapIndexed { i, tile ->
            Region(game.tileMap, Rectangle(tile.getColumn() - 6f, 0f, 13f, 12f), -1).also {
                it.startPosition = tile.position
                it.type = listOf("Grassland", "Desert", "Hill")[i]
            }
        }
    }

    private fun assign(regions: List<Region>, civs: List<Civilization>, seed: Int): Map<Civilization, Region> {
        val result = HashMap<Civilization, Region>()
        CivVStartAssignment.assign(regions, civs, game.ruleset, game.tileMap, false, Random(seed)) { civ, region -> result[civ] = region }
        return result
    }

    @Test
    fun `civs that want the coast get a region by the sea, the others take what is left`() {
        val regions = landMapWithSea()
        val seaCivs = listOf(game.addCiv("Start bias [Coast]"), game.addCiv("Start bias [Coast]"))
        val other = game.addCiv()
        for (seed in 1..10) {
            val result = assign(regions, seaCivs + other, seed)
            assertEquals(setOf(regions[0], regions[1]), seaCivs.map { result[it]!! }.toSet())
            assertEquals(regions[2], result[other])
        }
    }

    @Test
    fun `the civ that is placed first takes the only region by the sea`() {
        val regions = landMapWithSea().also { game.setTileTerrain(game.tileMap[it[1].startPosition!!].neighbors.first().position, "Grassland"); game.tileMap.setTransients(game.ruleset) }
        val first = game.addCiv("Start bias [Coast]", "Chooses its start region before the other civilizations with a Coast start bias")
        val second = game.addCiv("Start bias [Coast]")
        for (seed in 1..10) assertEquals(regions[0], assign(regions, listOf(second, first), seed)[first])
    }

    @Test
    fun `a civ that prefers a type of region gets one, one that avoids types does not`() {
        val regions = landMapWithSea()
        val wants = game.addCiv("Start bias [Desert]")
        val avoids = game.addCiv("Start bias [Avoid [Desert]]", "Start bias [Avoid [Hill]]")
        val rest = game.addCiv()
        for (seed in 1..10) {
            val result = assign(regions, listOf(rest, avoids, wants), seed)
            assertEquals("Desert", result[wants]!!.type)
            assertEquals("Grassland", result[avoids]!!.type)
        }
    }
}
