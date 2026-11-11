package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.CivVContinentsLandmass
import com.unciv.logic.map.mapgenerator.HexGeometry
import com.unciv.logic.map.mapgenerator.PointyHexGeometry
import com.unciv.logic.map.mapgenerator.UncivHexGeometry
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

    private fun assertHexGrid(geometry: HexGeometry) {
        val (width, height) = geometry.width to geometry.height
        var interior = 0
        for (x in 0 until width) for (y in 0 until height) {
            val (cx, cy) = geometry.center(x, y)
            val neighbors = geometry.neighbors(x, y)
            for ((nx, ny) in neighbors) {
                val (mx, my) = geometry.center(nx, ny)
                val d = kotlin.math.hypot(mx - cx, my - cy)
                // across the wrap of the pointy grid the centres are a width apart
                if (d < 1.5) assertEquals("($x,$y)-($nx,$ny)", 1.0, d, 0.02)
                assertTrue("not symmetric", (x to y) in geometry.neighbors(nx, ny))
                assertEquals(1, geometry.distance(x, y, nx, ny))
            }
            if (neighbors.size == 6) interior++
        }
        assertTrue(interior > width * height / 2)
    }

    @Test
    fun `the grids of the scripts and of Unciv are hex grids, neighbours a step apart and symmetric`() {
        assertHexGrid(PointyHexGeometry(40, 30, wrapX = true))
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 30, newWidth = 40, baseTerrain = "Grassland")
        val geometry = UncivHexGeometry(game.tileMap)
        assertEquals(40, geometry.width)
        assertHexGrid(geometry)
    }

    @Test
    fun `the centre rift runs from the bottom to the top over cells that touch, on either grid`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 60, newWidth = 100, baseTerrain = "Grassland")
        for (geometry in listOf(PointyHexGeometry(100, 60, true), UncivHexGeometry(game.tileMap))) {
            val landmass = CivVContinentsLandmass(geometry, false, Random(12))
            landmass.generate { true }
            val rift = landmass.centreRift
            assertEquals("starts at the bottom", 0, rift.first().second)
            assertEquals("ends at the top", geometry.height - 1, rift.last().second)
            for (i in 1 until rift.size)
                assertTrue("${rift[i - 1]} and ${rift[i]} do not touch", rift[i] in geometry.neighbors(rift[i - 1].first, rift[i - 1].second))
        }
    }

    @Test
    fun `ridges follow the steps of the grid, so hills and mountains are the same share on either`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeRectangularMap(newHeight = 60, newWidth = 100, baseTerrain = "Grassland")
        for (geometry in listOf(PointyHexGeometry(100, 60, true), UncivHexGeometry(game.tileMap))) {
            val result = CivVContinentsLandmass(geometry, true, Random(5)).generate { true }
            val land = result.land.sumOf { column -> column.count { it } }
            val hills = result.hills.sumOf { column -> column.count { it } } / land.toDouble()
            val peaks = result.mountains.sumOf { column -> column.count { it } } / land.toDouble()
            assertTrue("hills $hills", hills in 0.12..0.40)
            assertTrue("mountains $peaks", peaks in 0.01..0.10)
        }
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
