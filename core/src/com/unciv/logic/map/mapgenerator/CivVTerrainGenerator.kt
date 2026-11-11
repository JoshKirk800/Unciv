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
 *  The scripts' options for the temperature and the rainfall come from the map parameters (see [temperatureOption] and [rainfallOption]).
 */
class CivVTerrainGenerator(private val map: TileMap, private val rng: Random) {

    companion object {
        private const val MARSH = "Marsh"
        private const val OASIS = "Oasis"
        private const val FLOOD_PLAINS = "Flood plains"
        private const val ATOLL = "Atoll"

        // TerrainGenerator.Create defaults
        private const val DESERT_PERCENT_NORMAL = 32
        private const val PLAINS_PERCENT = 50
        private const val DESERT_BOTTOM_LATITUDE = 0.2
        private const val TERRAIN_GRAIN = 3
        private const val TEMPERATURE_SHIFT = 0.1
        private const val DESERT_SHIFT = 16

        // FeatureGenerator.Create defaults
        private const val JUNGLE_GRAIN = 5
        private const val FOREST_GRAIN = 6
        private const val CLUMP_GRAIN = 3
        private const val MARSH_GRAIN = 4

        /** The options of the scripts (World Temperature, Rainfall), 1 cool/arid, 2 normal, 3 hot/wet, from the map parameters:
         *  [com.unciv.logic.map.MapParameters.temperatureShift] beyond +-0.15 and [com.unciv.logic.map.MapParameters.vegetationRichness]
         *  below 0.3 or above 0.5 (the defaults are 0 and 0.4) */
        fun temperatureOption(shift: Float) = if (shift <= -0.15f) 1 else if (shift >= 0.15f) 3 else 2
        fun rainfallOption(vegetationRichness: Float) = if (vegetationRichness < 0.3f) 1 else if (vegetationRichness > 0.5f) 3 else 2
    }

    private val temperature = temperatureOption(map.mapParameters.temperatureShift)
    private val rainfall = rainfallOption(map.mapParameters.vegetationRichness)

    // TerrainGenerator.Create: the latitudes and the share of desert move with the temperature
    private val desertPercent = DESERT_PERCENT_NORMAL + when (temperature) { 1 -> -DESERT_SHIFT; 3 -> DESERT_SHIFT; else -> 0 }
    private val snowLatitude = 0.75 + if (temperature == 3) TEMPERATURE_SHIFT * 0.5 else 0.0
    private val tundraLatitude = 0.6 + when (temperature) { 1 -> -TEMPERATURE_SHIFT * 1.5; 3 -> TEMPERATURE_SHIFT; else -> 0.0 }
    private val desertTopLatitude = 0.5 + when (temperature) { 1 -> -TEMPERATURE_SHIFT; 3 -> TEMPERATURE_SHIFT; else -> 0.0 }
    private val grassLatitude = 0.1 - if (temperature != 2) TEMPERATURE_SHIFT * 0.5 else 0.0

    // FeatureGenerator.Create: the rainfall moves jungle, forest, marsh and oases (an arid land has no jungle band to speak of)
    private val junglePercent = 65 + when (rainfall) { 1 -> -20; 3 -> 20; else -> 0 }
    private val jungleFactor = when (rainfall) { 1 -> 6; 3 -> 2; else -> 5 }
    private val forestPercent = 31 + when (rainfall) { 1 -> -15; 3 -> 15; else -> 0 }
    private val clumpHeight = 90 + when (rainfall) { 1 -> -5; 3 -> 5; else -> 0 }
    private val marshPercent = when (rainfall) { 1 -> 8 / 1.5; 3 -> 8 * 1.5; else -> 8.0 }
    private val oasisPercent = when (rainfall) { 1 -> 6 / 1.5; 3 -> 6 * 1.5; else -> 6.0 }

    private val tiles = map.values.toList()
    private val minColumn = tiles.minOf { it.getColumn() }
    private val minRow = tiles.minOf { it.getRow() }
    private val gridWidth = tiles.maxOf { it.getColumn() } - minColumn + 1
    private val gridHeight = tiles.maxOf { it.getRow() } - minRow + 1
    private val wrapX = map.mapParameters.worldWrap
    private val maxLatitude = map.maxLatitude.coerceAtLeast(1)
    private val worldSize = CivVWorldSize.forTiles(gridWidth * gridHeight)

    private fun x(tile: Tile) = tile.getColumn() - minColumn
    private fun y(tile: Tile) = tile.getRow() - minRow

    /** The latitude of the feature generator: 0 at the equator, 1 at the poles */
    private fun latitude(tile: Tile) = abs(tile.latitude).toDouble() / maxLatitude

    private fun fractal(grain: Int) = CivVFractal(gridWidth, gridHeight, grain, wrapX, false, rng)

    /** TerrainGenerator:GenerateTerrain */
    fun generateBaseTerrain() {
        val deserts = fractal(TERRAIN_GRAIN)
        val desertTop = deserts.getHeightFromPercent(100)
        val desertBottom = deserts.getHeightFromPercent(maxOf(0, floor(100.0 - desertPercent).toInt()))
        val plains = fractal(TERRAIN_GRAIN)
        val plainsTop = plains.getHeightFromPercent(100)
        val plainsBottom = plains.getHeightFromPercent(maxOf(0, floor(100.0 - PLAINS_PERCENT).toInt()))
        val variation = fractal(TERRAIN_GRAIN)

        for (tile in tiles) {
            if (!tile.isLand || tile.isImpassible()) continue
            val lat = (latitude(tile) + (128 - variation.getHeight(x(tile), y(tile))) / (255.0 * 5.0)).coerceIn(0.0, 1.0)
            val terrain = when {
                lat >= snowLatitude -> Constants.snow
                lat >= tundraLatitude -> Constants.tundra
                lat < grassLatitude -> Constants.grassland
                else -> {
                    val desertValue = deserts.getHeight(x(tile), y(tile))
                    val plainsValue = plains.getHeight(x(tile), y(tile))
                    if (desertValue in desertBottom..desertTop && lat >= DESERT_BOTTOM_LATITUDE && lat < desertTopLatitude)
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
        val grainChange = worldSize.grainChange
        addAtolls()
        val jungles = fractal(JUNGLE_GRAIN + grainChange)
        val forests = fractal(FOREST_GRAIN + grainChange)
        val clumps = fractal(CLUMP_GRAIN)
        val marsh = fractal(MARSH_GRAIN)
        val jungleBottom = jungles.getHeightFromPercent(((100 - junglePercent) / 2.0).toInt())
        val jungleTop = jungles.getHeightFromPercent(((100 + junglePercent) / 2.0).toInt())
        val jungleRange = (jungleTop - jungleBottom) * jungleFactor
        val forestLevel = forests.getHeightFromPercent(100 - forestPercent)
        val clumpLevel = clumps.getHeightFromPercent(clumpHeight)
        val marshLevel = marsh.getHeightFromPercent((100 - marshPercent).toInt())

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
                tile.neighbors.none { OASIS in it.terrainFeatures } && rng.nextInt(100) <= oasisPercent)
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

    /**
     *  FeatureGenerator:AddAtolls: on a world with a big ocean (more than a quarter of the map), a few single-tile coast sites
     *  get an atoll: coast tiles with exactly one land neighbour that is not tundra, snow or ice, sorted by the size of that landmass
     *  (1-2, 3-7, 8-16, 17-40, 41-75 tiles; the sites by bigger landmasses are not used) and thinned out to a quarter, a fifth, ... of each class.
     *  The number of atolls is the world size's target plus a random number up to it (2 for Duel to 12 for Huge); a roll picks
     *  a class (40 % smallest, 25 %, 15 %, 10 %, 10 %) and falls back to the smaller classes when it is used up.
     */
    private fun addAtolls() {
        if (ATOLL !in map.ruleset!!.terrains) return
        // The biggest body of water (oceans and coast, no lakes) must be more than a quarter of the map
        val water = tiles.filter { it.isWater && it.baseTerrain != "Lakes" }.toHashSet()
        var biggest = 0
        val seen = HashSet<Tile>()
        for (start in water) {
            if (!seen.add(start)) continue
            var size = 1
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) for (n in queue.removeFirst().neighbors) if (n in water && seen.add(n)) { size++; queue.add(n) }
            biggest = maxOf(biggest, size)
        }
        if (biggest <= tiles.size / 4) return

        val classes = Array(5) { ArrayList<Tile>() } // alpha .. epsilon
        for (tile in tiles) {
            if (!tile.isWater || tile.baseTerrain != Constants.coast || tile.terrainFeatures.isNotEmpty()) continue
            val land = tile.neighbors.filter { !it.isWater }.toList()
            if (land.size != 1) continue
            val neighbour = land.first()
            if (neighbour.baseTerrain == Constants.tundra || neighbour.baseTerrain == Constants.snow || Constants.ice in neighbour.terrainFeatures) continue
            val area = if (neighbour.isImpassible() && Constants.hill !in neighbour.terrainFeatures) 0 else map.continentSizes[neighbour.getContinent()] ?: 0
            val index = when {
                area >= 76 -> continue
                area >= 41 -> 4
                area >= 17 -> 3
                area >= 8 -> 2
                area >= 3 -> 1
                area >= 1 -> 0
                else -> continue
            }
            classes[index].add(tile)
        }
        val shuffled = classes.map { it.shuffled(rng) }
        val limits = intArrayOf(4, 5, 4, 3, 4).mapIndexed { i, d -> (shuffled[i].size + d - 1) / d }.toIntArray()
        val used = IntArray(5)

        fun take(index: Int): Tile? {
            if (limits[index] <= 0) return null
            limits[index]--
            return shuffled[index].getOrNull(used[index]++)
        }
        val target = worldSize.atolls
        repeat(target + rng.nextInt(target)) {
            val roll = 1 + rng.nextInt(100)
            // The class the roll asks for, then each smaller one
            val first = when {
                roll <= 40 -> 0
                roll <= 65 -> 1
                roll <= 80 -> 2
                roll <= 90 -> 3
                else -> 4
            }
            val tile = (maxOf(first, 1) downTo 0).firstNotNullOfOrNull { take(it) } // a roll for the smallest class that finds it used up asks the next
            tile?.addTerrainFeature(ATOLL)
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
