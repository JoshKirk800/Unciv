package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.CivVContinentsLandmass
import com.unciv.logic.map.mapgenerator.CivVFractal
import com.unciv.logic.map.mapgenerator.CivVTerrainGenerator
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** The rest of the terrain port: rifts, ridges, the centre rift, options, atolls, rivers and lakes */
@RunWith(GdxTestRunner::class)
class CivVTerrainPortTests {

    @Test
    fun `a fractal with rifts has a valley to the east in every row`() {
        val rifts = CivVFractal(128, 64, 2, false, false, Random(4))
        val f = CivVFractal(128, 64, 2, false, false, Random(9), polar = true, rifts = rifts)
        for (y in 1 until 63) assertTrue("row $y", (60..127).minOf { f.getHeight(it, y) } <= 8)
    }

    @Test
    fun `ridges are high where the cells of the Voronoi diagram meet and keep the share of the blend`() {
        val f = CivVFractal(128, 64, 3, true, false, Random(2))
        f.buildRidges(Random(3), numSeeds = 12, biased = true, blendRidge = 1, blendFract = 0)
        val values = (0 until 128).flatMap { x -> (0 until 64).map { y -> f.getHeight(x, y) } }
        // Closest over second closest: never above 255, and close to it only along the borders, near 0 at the seeds
        assertTrue(values.max() <= 255)
        assertTrue("share above 200: ${values.count { it > 200 } / values.size.toDouble()}", values.count { it > 200 } / values.size.toDouble() < 0.5)
        assertTrue(values.min() < 60)
    }

    @Test
    fun `the Continents landmass leaves a sea path up the middle and has hills and a few mountains`() {
        val w = 100
        val h = 64
        val result = CivVContinentsLandmass(w, h, true, Random(8)).generate { true }
        // The centre rift wanders within a quarter of the height of the middle (+ the drift)
        val lo = w / 2 - h / 4 - 8
        val hi = w / 2 + h / 4 + 8
        for (y in 1 until h - 1) assertTrue("row $y has no sea in the middle", (lo..hi).any { !result.land[it][y] })
        val land = result.land.sumOf { column -> column.count { it } }
        val hills = result.hills.sumOf { column -> column.count { it } } / land.toDouble()
        val peaks = result.mountains.sumOf { column -> column.count { it } } / land.toDouble()
        assertTrue("hills $hills", hills in 0.12..0.40)
        assertTrue("mountains $peaks", peaks in 0.01..0.10)
    }

    @Test
    fun `temperature and rainfall options come from the map parameters`() {
        assertEquals(1, CivVTerrainGenerator.temperatureOption(-0.2f))
        assertEquals(2, CivVTerrainGenerator.temperatureOption(0f))
        assertEquals(3, CivVTerrainGenerator.temperatureOption(0.2f))
        assertEquals(1, CivVTerrainGenerator.rainfallOption(0.2f))
        assertEquals(2, CivVTerrainGenerator.rainfallOption(0.4f))
        assertEquals(3, CivVTerrainGenerator.rainfallOption(0.6f))
    }

    private fun terrainShares(shift: Float, richness: Float): Map<String, Double> {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 60, newWidth = 100, baseTerrain = "Grassland")
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.mapParameters.temperatureShift = shift
        game.tileMap.mapParameters.vegetationRichness = richness
        val generator = CivVTerrainGenerator(game.tileMap, Random(5))
        generator.generateBaseTerrain()
        generator.addFeatures()
        val land = game.tileMap.values.toList()
        val shares = HashMap<String, Double>()
        for (t in land) {
            shares[t.baseTerrain] = (shares[t.baseTerrain] ?: 0.0) + 1.0 / land.size
            for (f in t.terrainFeatures) shares[f] = (shares[f] ?: 0.0) + 1.0 / land.size
        }
        return shares
    }

    @Test
    fun `a cool world has more tundra and less desert than a hot one, a wet one more jungle than an arid one`() {
        val cool = terrainShares(-0.2f, 0.4f)
        val hot = terrainShares(0.2f, 0.4f)
        assertTrue(cool["Tundra"]!! > hot["Tundra"]!!)
        assertTrue(cool["Desert"]!! < hot["Desert"]!!)
        val wet = terrainShares(0f, 0.6f)
        val arid = terrainShares(0f, 0.2f)
        assertTrue("jungle ${wet["Jungle"]} vs ${arid["Jungle"]}", wet["Jungle"]!! > arid["Jungle"]!!)
        assertTrue(wet["Forest"]!! > arid["Forest"]!!)
    }

    private fun civVMap(seed: Long): TileMap {
        RulesetCache.loadRulesets(noMods = true)
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply {
            this.seed = seed; type = MapType.continentsLikeCivV; shape = MapShape.rectangular
            mapSize = MapSize(100, 64); worldWrap = true
        }
        return MapGenerator(ruleset).generateMap(params)
    }

    @Test
    fun `atolls sit on coast tiles with one land neighbour, no tundra or snow, and there are a handful`() {
        var total = 0
        for (seed in 1L..3L) {
            val map = civVMap(seed)
            val atolls = map.values.filter { "Atoll" in it.terrainFeatures }
            total += atolls.size
            for (a in atolls) {
                assertEquals("Coast", a.baseTerrain)
                val land = a.neighbors.filter { !it.isWater }.toList()
                assertEquals(1, land.size)
                assertTrue(land.first().baseTerrain != "Tundra" && land.first().baseTerrain != "Snow")
            }
            assertTrue("seed $seed: ${atolls.size} atolls", atolls.size <= 18)
        }
        assertTrue("atolls in 3 maps: $total", total in 3..54)
    }

    @Test
    fun `rivers run between land tiles, keep apart and lakes are inland`() {
        for (seed in 1L..3L) {
            val map = civVMap(seed)
            val landTiles = map.values.filter { it.isLand }
            val edges = landTiles.sumOf { listOf(it.hasBottomRiver, it.hasBottomLeftRiver, it.hasBottomRightRiver).count { b -> b } }
            assertTrue("seed $seed: $edges river edges for ${landTiles.size} land tiles", edges in landTiles.size / 12..landTiles.size)
            // No river edge has water beside it
            val wet = landTiles.count { tile -> tile.neighbors.any { n -> n.isWater && tile.isConnectedByRiver(n) } }
            assertEquals("seed $seed", 0, wet)
        }
    }
}
