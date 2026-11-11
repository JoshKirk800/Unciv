package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.CivVFractal
import com.unciv.logic.map.mapgenerator.CivVTerrainGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/** CvFractal (Fractal.Create), TerrainGenerator.lua and FeatureGenerator.lua */
@RunWith(GdxTestRunner::class)
class CivVTerrainGeneratorTests {

    @Test
    fun `a fractal cut at a percentile has about that share of its cells below the cut`() {
        val fractal = CivVFractal(128, 80, grain = 3, wrapX = true, wrapY = false, rng = Random(7))
        for (percent in listOf(10, 32, 50, 68, 90)) {
            val cut = fractal.getHeightFromPercent(percent)
            val below = (0 until 128).sumOf { x -> (0 until 80).count { y -> fractal.getHeight(x, y) < cut } }
            assertEquals("below the ${percent} % cut", percent / 100.0, below / (128.0 * 80), 0.1)
        }
    }

    @Test
    fun `a fractal that wraps has no seam and a lower grain is smoother`() {
        fun roughness(grain: Int): Pair<Double, Double> {
            val f = CivVFractal(128, 64, grain, wrapX = true, wrapY = false, rng = Random(3))
            // the last column is next to the first through the seam
            val seam = (0 until 64).sumOf { y -> abs(f.getHeight(127, y) - f.getHeight(0, y)) } / 64.0
            val inside = (0 until 127).sumOf { x -> (0 until 64).sumOf { y -> abs(f.getHeight(x, y) - f.getHeight(x + 1, y)) } } / (127.0 * 64)
            return seam to inside
        }
        val (seam, smooth) = roughness(2)
        assertTrue("seam $seam, inside $smooth", seam < 3 * smooth + 5)
        assertTrue(smooth < roughness(6).second)
    }

    @Test
    fun `the poles of a polar fractal are nearly zero`() {
        // the edge row is not set to zero in the last pass, it gets the small offset of the original
        val f = CivVFractal(100, 64, 3, wrapX = false, wrapY = false, rng = Random(1), polar = true)
        assertTrue((0 until 100).maxOf { f.getHeight(it, 0) } < 20)
    }

    private fun generate(flat: Boolean = false): com.unciv.logic.map.TileMap {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 60, newWidth = 100, baseTerrain = "Grassland")
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        val generator = CivVTerrainGenerator(game.tileMap, Random(5))
        generator.generateBaseTerrain()
        generator.addFeatures()
        return game.tileMap
    }

    @Test
    fun `terrain follows latitude, snow and tundra at the poles, grass at the equator, no jungle but on plains`() {
        val map = generate()
        val maxLat = map.maxLatitude
        val land = map.values.filter { it.isLand }
        assertTrue(land.filter { abs(it.latitude) >= 0.85 * maxLat }.all { it.baseTerrain == "Snow" || it.baseTerrain == "Tundra" })
        assertTrue(land.filter { abs(it.latitude) < 0.02 * maxLat }.all { it.baseTerrain == "Grassland" || it.baseTerrain == "Plains" })
        assertTrue("jungle always sits on plains", land.filter { "Jungle" in it.terrainFeatures }.all { it.baseTerrain == "Plains" })
        assertTrue("no desert beyond 50 % of the way to the pole",
            land.filter { abs(it.latitude) > 0.6 * maxLat }.none { it.baseTerrain == "Desert" })
    }

    @Test
    fun `about a third of the land gets forest and jungle and marsh stays rare`() {
        val map = generate()
        val land = map.values.filter { it.isLand }
        val forest = land.count { "Forest" in it.terrainFeatures } / land.size.toDouble()
        assertTrue("forest share $forest", forest in 0.1..0.4)
        val marsh = land.count { "Marsh" in it.terrainFeatures } / land.size.toDouble()
        assertTrue("marsh share $marsh", marsh < 0.1)
    }

    @Test
    fun `the map generator of a Civ V ruleset follows the latitude bands of the scripts`() {
        com.unciv.models.ruleset.RulesetCache.loadRulesets(noMods = true)
        val ruleset = com.unciv.models.ruleset.RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val params = MapParameters().apply {
            seed = 11L; type = MapType.pangaea; shape = MapShape.rectangular
            mapSize = MapSize(100, 60); worldWrap = true; waterThreshold = 0.1f
        }
        val map = com.unciv.logic.map.mapgenerator.MapGenerator(ruleset).generateMap(params)
        val land = map.values.filter { it.isLand && !it.isImpassible() }
        val maxLat = map.maxLatitude
        // Snow from 75 % of the way to the pole, less the 10 % the noise moves it: nothing but snow and tundra beyond 85 %,
        // nothing of them within 50 %; desert only in the band from 10 % to 60 %
        assertTrue(land.filter { abs(it.latitude) >= 0.86 * maxLat }.all { it.baseTerrain == "Snow" || it.baseTerrain == "Tundra" })
        assertTrue(land.filter { abs(it.latitude) < 0.49 * maxLat }.none { it.baseTerrain == "Snow" || it.baseTerrain == "Tundra" })
        assertTrue(land.filter { it.baseTerrain == "Desert" }.all { abs(it.latitude).toDouble() in (0.09 * maxLat)..(0.61 * maxLat) })
        assertTrue("jungle always sits on plains", land.filter { "Jungle" in it.terrainFeatures }.all { it.baseTerrain == "Plains" })
    }
}
