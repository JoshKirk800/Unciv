package com.unciv.logic.map.mapgenerator

import com.unciv.Constants
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 *  The terrain and features of the Civ V map scripts: TerrainGenerator.lua (the base terrain of land by latitude) and
 *  FeatureGenerator.lua (flood plains, oases, ice, marsh, jungle, forest), for rulesets that set `terrainGeneratorLikeCivV`.
 *
 *  It names the terrains and features of the Civ V rulesets. Mountains and hills stay as the elevation generator made them.
 *  Rivers have to be there before the features: flood plains need one, oases and (on the edge) tundra do not like them.
 *  Not ported: atolls (AddAtolls), the temperature and rainfall options (always the normal setting, as the scripts' defaults).
 */
class CivVTerrainGenerator(private val map: TileMap, private val rng: Random) {

    companion object {
        private const val MARSH = "Marsh"
        private const val OASIS = "Oasis"
        private const val FLOOD_PLAINS = "Flood plains"

        // TerrainGenerator.Create defaults (normal temperature)
        private const val DESERT_PERCENT = 32
        private const val PLAINS_PERCENT = 50
        private const val SNOW_LATITUDE = 0.75
        private const val TUNDRA_LATITUDE = 0.6
        private const val GRASS_LATITUDE = 0.1
        private const val DESERT_BOTTOM_LATITUDE = 0.2
        private const val DESERT_TOP_LATITUDE = 0.5
        private const val TERRAIN_GRAIN = 3

        // FeatureGenerator.Create defaults (normal rainfall)
        private const val JUNGLE_GRAIN = 5
        private const val FOREST_GRAIN = 6
        private const val CLUMP_GRAIN = 3
        private const val MARSH_GRAIN = 4
        private const val JUNGLE_PERCENT = 65
        private const val JUNGLE_FACTOR = 5
        private const val FOREST_PERCENT = 31
        private const val CLUMP_HEIGHT = 90
        private const val MARSH_PERCENT = 8
        private const val OASIS_PERCENT = 6
    }

    private val tiles = map.values.toList()
    private val minColumn = tiles.minOf { it.getColumn() }
    private val minRow = tiles.minOf { it.getRow() }
    private val gridWidth = tiles.maxOf { it.getColumn() } - minColumn + 1
    private val gridHeight = tiles.maxOf { it.getRow() } - minRow + 1
    private val wrapX = map.mapParameters.worldWrap
    private val maxLatitude = map.maxLatitude.coerceAtLeast(1)

    private fun x(tile: Tile) = tile.getColumn() - minColumn
    private fun y(tile: Tile) = tile.getRow() - minRow

    /** The latitude of the feature generator: 0 at the equator, 1 at the poles */
    private fun latitude(tile: Tile) = abs(tile.latitude).toDouble() / maxLatitude

    private fun fractal(grain: Int) = CivVFractal(gridWidth, gridHeight, grain, wrapX, false, rng)

    /** TerrainGenerator:GenerateTerrain */
    fun generateBaseTerrain() {
        val deserts = fractal(TERRAIN_GRAIN)
        val desertTop = deserts.getHeightFromPercent(100)
        val desertBottom = deserts.getHeightFromPercent(maxOf(0, floor(100.0 - DESERT_PERCENT).toInt()))
        val plains = fractal(TERRAIN_GRAIN)
        val plainsTop = plains.getHeightFromPercent(100)
        val plainsBottom = plains.getHeightFromPercent(maxOf(0, floor(100.0 - PLAINS_PERCENT).toInt()))
        val variation = fractal(TERRAIN_GRAIN)

        for (tile in tiles) {
            if (!tile.isLand || tile.isImpassible()) continue
            val lat = (latitude(tile) + (128 - variation.getHeight(x(tile), y(tile))) / (255.0 * 5.0)).coerceIn(0.0, 1.0)
            val terrain = when {
                lat >= SNOW_LATITUDE -> Constants.snow
                lat >= TUNDRA_LATITUDE -> Constants.tundra
                lat < GRASS_LATITUDE -> Constants.grassland
                else -> {
                    val desertValue = deserts.getHeight(x(tile), y(tile))
                    val plainsValue = plains.getHeight(x(tile), y(tile))
                    if (desertValue in desertBottom..desertTop && lat >= DESERT_BOTTOM_LATITUDE && lat < DESERT_TOP_LATITUDE)
                        Constants.desert
                    else if (plainsValue in plainsBottom..plainsTop) Constants.plains
                    else Constants.grassland
                }
            }
            // Only the base terrain: the hills the elevation generator made stay
            val hill = tile.hasHill
            tile.removeTerrainFeatures()
            tile.baseTerrain = terrain
            if (hill) tile.setTerrainFeatures(listOf(Constants.hill))
            tile.setTerrainTransients()
        }
    }

    private val Tile.hasHill get() = Constants.hill in terrainFeatures

    /** FeatureGenerator:AddFeatures and AdjustTerrainTypes. The rivers have to be made before. */
    fun addFeatures() {
        val grainChange = when {
            gridWidth * gridHeight < 2500 -> -1 // Duel, Tiny
            gridWidth * gridHeight < 5000 -> 0 // Small, Standard
            else -> 1 // Large, Huge
        }
        val jungles = fractal(JUNGLE_GRAIN + grainChange)
        val forests = fractal(FOREST_GRAIN + grainChange)
        val clumps = fractal(CLUMP_GRAIN)
        val marsh = fractal(MARSH_GRAIN)
        val jungleBottom = jungles.getHeightFromPercent((100 - JUNGLE_PERCENT) / 2)
        val jungleTop = jungles.getHeightFromPercent((100 + JUNGLE_PERCENT) / 2)
        val jungleRange = (jungleTop - jungleBottom) * JUNGLE_FACTOR
        val forestLevel = forests.getHeightFromPercent(100 - FOREST_PERCENT)
        val clumpLevel = clumps.getHeightFromPercent(CLUMP_HEIGHT)
        val marshLevel = marsh.getHeightFromPercent(100 - MARSH_PERCENT)

        for (tile in tiles.sortedWith(compareBy({ y(it) }, { x(it) }))) {
            val lat = latitude(tile)
            if (tile.isWater) {
                addIce(tile, lat)
                continue
            }
            if (tile.isImpassible()) continue
            val flat = !tile.hasHill
            val terrain = tile.baseTerrain
            val hasFeature = { tile.terrainFeatures.any { it != Constants.hill } }
            val river = tile.isAdjacentToRiver()

            if (terrain == Constants.desert && flat && river && !hasFeature())
                tile.addTerrainFeature(FLOOD_PLAINS)
            if (!hasFeature() && terrain == Constants.desert && flat && !tile.isAdjacentToCoast() && !river &&
                tile.neighbors.none { OASIS in it.terrainFeatures } && rng.nextInt(100) <= OASIS_PERCENT)
                tile.addTerrainFeature(OASIS)
            if (!hasFeature() && terrain == Constants.grassland && flat && marsh.getHeight(x(tile), y(tile)) >= marshLevel)
                tile.addTerrainFeature(MARSH)
            if (!hasFeature() && terrain == Constants.grassland) {
                val height = jungles.getHeight(x(tile), y(tile))
                if (height <= jungleTop && height >= jungleBottom + jungleRange * lat)
                    tile.addTerrainFeature(Constants.jungle)
            }
            if (!hasFeature() && (terrain == Constants.grassland || terrain == Constants.plains || terrain == Constants.tundra) &&
                (forests.getHeight(x(tile), y(tile)) >= forestLevel || clumps.getHeight(x(tile), y(tile)) >= clumpLevel))
                tile.addTerrainFeature(Constants.forest)
        }

        // AdjustTerrainTypes: plains under the jungle, and softer arctic rivers
        for (tile in tiles) {
            if (tile.isWater || tile.isImpassible()) continue
            if (Constants.jungle in tile.terrainFeatures) tile.baseTerrain = Constants.plains
            else if (tile.isAdjacentToRiver()) {
                if (tile.baseTerrain == Constants.tundra) tile.baseTerrain = Constants.plains
                else if (tile.baseTerrain == Constants.snow) tile.baseTerrain = Constants.tundra
            }
            tile.setTerrainTransients()
        }
    }

    /** FeatureGenerator:AddIceAtPlot: always on the rows at the edge of a map that wraps, otherwise more the closer to the pole */
    private fun addIce(tile: Tile, lat: Double) {
        if (tile.baseTerrain != Constants.ocean && tile.baseTerrain != Constants.coast) return
        if (tile.terrainFeatures.isNotEmpty()) return
        val onEdgeRow = map.mapParameters.shape == MapShape.rectangular && wrapX && (y(tile) == 0 || y(tile) == gridHeight - 1)
        if (onEdgeRow) {
            tile.addTerrainFeature(Constants.ice)
            return
        }
        val roll = rng.nextInt(100) / 100.0
        if (roll < 8 * (lat - 0.875) || roll < 4 * (lat - 0.75))
            tile.addTerrainFeature(Constants.ice)
    }
}
