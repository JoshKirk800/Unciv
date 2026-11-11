package com.unciv.logic.map.mapgenerator.resourceplacement

import com.unciv.Constants
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TileResource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 *  The parts of AssignStartingPlots:PlaceStrategicAndBonusResources and PlaceResourcesAndCityStates that Unciv has no counterpart for,
 *  for rulesets that set `resourceExtrasLikeCivV`: PlaceSexyBonusAtCivStarts, AddExtraBonusesToHillsRegions, AddModernMinorStrategicsToCityStates,
 *  the strategic top-ups at the end of the strategic placement and FixSugarJungles. It names the resources of Civ V.
 */
class CivVResourceExtras(
    private val tileMap: TileMap,
    private val regions: List<Region>,
    private val tileData: TileDataMap,
    private val ruleset: Ruleset,
    private val rng: Random
) {
    private fun resource(name: String): TileResource? = ruleset.tileResources[name]

    private fun canHold(resource: TileResource, tile: Tile) =
        tile.resource == null && tile.naturalWonder == null &&
            resource.terrainsCanBeFoundOn.any { it == tile.baseTerrain || it in tile.terrainFeatures }

    private fun hasHill(tile: Tile) = Constants.hill in tile.terrainFeatures
    private fun features(tile: Tile) = tile.terrainFeatures.filter { it != Constants.hill }

    // region: PlaceSexyBonusAtCivStarts

    /** One bonus of the kind of the region (two sheep, if there is room) on the third ring of each start, else one fish */
    fun placeBonusAtStarts() {
        val kindOfRegion = mapOf(
            "Tundra" to "Deer", "Jungle" to "Bananas", "Forest" to "Deer", "Desert" to "Wheat",
            "Hill" to "Sheep", "Plains" to "Wheat", "Grassland" to "Cattle", "Hybrid" to "Cattle"
        )
        for (region in regions) {
            val name = kindOfRegion[region.type] ?: continue
            val bonus = resource(name) ?: continue
            val ring = tileMap[region.startPosition!!].getTilesAtDistance(3).filter { it.resource == null && "Oasis" !in it.terrainFeatures }.toList()
            val candidates = ring.filter { tile ->
                val f = features(tile)
                when (name) {
                    "Deer" -> Constants.forest in f || (tile.baseTerrain == Constants.tundra && !tile.isWater && !hasHill(tile) && f.isEmpty())
                    "Bananas" -> Constants.jungle in f
                    "Wheat" -> !tile.isWater && !hasHill(tile) && (
                        (tile.baseTerrain == Constants.plains && f.isEmpty()) || "Flood plains" in f ||
                            (tile.baseTerrain == Constants.desert && f.isEmpty() && tile.isAdjacentTo(Constants.freshWater)))
                    "Sheep" -> hasHill(tile) && f.isEmpty() && tile.baseTerrain in listOf(Constants.plains, Constants.grassland, Constants.tundra)
                    else -> !tile.isWater && !hasHill(tile) && f.isEmpty() && tile.baseTerrain == Constants.grassland
                }
            }.shuffled(rng)
            if (candidates.isNotEmpty()) {
                var placed = 0
                for (tile in candidates) if (canHold(bonus, tile)) { tile.setTileResource(bonus, majorDeposit = false); placed = 1; break }
                if (candidates.size > 1 && name == "Sheep") for (tile in candidates) if (canHold(bonus, tile)) { tile.setTileResource(bonus, majorDeposit = false); break }
                if (placed == 0) placeFish(ring)
            } else placeFish(ring)
        }
    }

    private fun placeFish(ring: List<Tile>) {
        val fish = resource("Fish") ?: return
        ring.filter { it.isWater && it.baseTerrain == Constants.coast && "Atoll" !in it.terrainFeatures && Constants.ice !in it.terrainFeatures && canHold(fish, it) }
            .shuffled(rng).firstOrNull()?.setTileResource(fish, majorDeposit = false)
    }

    // endregion

    // region: AddExtraBonusesToHillsRegions

    /**
     *  ProcessResourceList on a list of tiles: [frequency] tiles to a deposit, each of [name] with a bonus impact of [minRadius] to [maxRadius],
     *  on tiles with no bonus impact first, then on the ones with the least.
     */
    private fun processList(name: String, frequency: Double, list: List<Tile>, minRadius: Int, maxRadius: Int) {
        val bonus = resource(name) ?: return
        val shuffled = list.shuffled(rng)
        var left = ceil(shuffled.size / frequency).toInt()
        var index = 0
        // First pass: tiles without impact, in order
        while (left > 0 && index < shuffled.size) {
            val tile = shuffled[index++]
            if (!canHold(bonus, tile) || tileData[tile]!!.impacts.containsKey(ImpactType.Bonus)) continue
            tile.setTileResource(bonus, majorDeposit = false)
            tileData.placeImpact(ImpactType.Bonus, tile, minRadius + if (maxRadius > minRadius) rng.nextInt(maxRadius - minRadius + 1) else 0)
            left--
        }
        // Then the least impacted
        while (left > 0) {
            val tile = shuffled.filter { canHold(bonus, it) }.minByOrNull { tileData[it]!!.impacts[ImpactType.Bonus] ?: 0 } ?: break
            tile.setTileResource(bonus, majorDeposit = false)
            tileData.placeImpact(ImpactType.Bonus, tile, minRadius + if (maxRadius > minRadius) rng.nextInt(maxRadius - minRadius + 1) else 0)
            left--
        }
    }

    /** Hills regions get more bonus resources, the more the hills outweigh the farmland the fewer of them */
    fun addBonusesToHillsRegions() {
        for (region in regions.filter { it.type == "Hill" }.shuffled(rng)) {
            val area = region.tiles.count { it.baseTerrain != Constants.mountain }.coerceAtLeast(1)
            val hillsRatio = ((region.terrainCounts["Hill"] ?: 0) + (region.terrainCounts["Mountain"] ?: 0)).toDouble() / area
            val farmRatio = ((region.terrainCounts["Grassland"] ?: 0) + (region.terrainCounts["Plains"] ?: 0)).toDouble() / area
            val infertility = 1 + max(0.0, hillsRatio - farmRatio)

            val dryHills = ArrayList<Tile>(); val flatPlains = ArrayList<Tile>(); val flatGrass = ArrayList<Tile>()
            val flatTundra = ArrayList<Tile>(); val jungles = ArrayList<Tile>(); val forests = ArrayList<Tile>()
            for (tile in region.tiles) {
                if (tile.isWater || tile.resource != null || tile.baseTerrain == Constants.mountain) continue
                val f = features(tile)
                when {
                    Constants.jungle in f -> jungles.add(tile)
                    Constants.forest in f -> forests.add(tile)
                    "Flood plains" in f -> flatPlains.add(tile)
                    f.isEmpty() && hasHill(tile) ->
                        if (tile.baseTerrain in listOf(Constants.grassland, Constants.plains, Constants.tundra) && !tile.isAdjacentTo(Constants.freshWater)) dryHills.add(tile)
                    f.isEmpty() -> when {
                        tile.baseTerrain == Constants.plains -> flatPlains.add(tile)
                        tile.baseTerrain == Constants.desert && tile.isAdjacentTo(Constants.freshWater) -> flatPlains.add(tile)
                        tile.baseTerrain == Constants.grassland -> flatGrass.add(tile)
                        tile.baseTerrain == Constants.tundra -> flatTundra.add(tile)
                    }
                }
            }
            if (dryHills.isNotEmpty()) processList("Sheep", 9 / infertility, dryHills, 0, 1)
            if (jungles.isNotEmpty()) processList("Bananas", 14 / infertility, jungles, 1, 2)
            if (flatTundra.isNotEmpty()) processList("Deer", 14 / infertility, flatTundra, 0, 1)
            if (flatPlains.isNotEmpty()) processList("Wheat", 18 / infertility, flatPlains, 0, 2)
            if (flatGrass.isNotEmpty()) processList("Cattle", 20 / infertility, flatGrass, 0, 2)
            if (forests.isNotEmpty()) processList("Deer", 24 / infertility, forests, 1, 2)
        }
    }

    // endregion

    // region: AddModernMinorStrategicsToCityStates

    /** Each city state may get a small deposit of coal, oil or aluminum (3 in 4): by the lists, best first, of the tiles within 3 */
    fun addModernMinorStrategicsToCityStates() {
        val lists = mapOf(
            "Coal" to listOf(4, 5, 14, 12, 11, 10),
            "Oil" to listOf(10, 2, 14, 15, 12, 11),
            "Aluminum" to listOf(4, 5, 14, 10, 11, 12)
        )
        for (start in tileMap.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.values.map { it.first() }) {
            val name = when (rng.nextInt(4)) { 1 -> "Coal"; 2 -> "Oil"; 3 -> "Aluminum"; else -> continue }
            val resource = resource(name) ?: continue
            val siteLists = CivVPlotLists.listsAtSite(start, 3)
            for (index in lists[name]!!) {
                val tile = siteLists[index].shuffled(rng).firstOrNull { canHold(resource, it) } ?: continue
                tile.setTileResource(resource, majorDeposit = false)
                break
            }
        }
    }

    // endregion

    // region: the top-ups

    /** The units of [name] placed so far, as AssignStartingPlots counts them (the sum of the amounts) */
    private fun unitsOf(name: String) = tileMap.values.filter { it.resource == name }.sumOf { max(it.resourceAmount, 1) }

    /**
     *  The checks at the end of PlaceStrategicAndBonusResources: iron below 8 units gets a deposit on hills, below 4 per civ one on land, horses below
     *  4 per civ one on plains and one on grass, coal and aluminum and oil and uranium the same by their own counts, each a major deposit on
     *  the first fitting tile of a shuffled list, one that has no strategic impact if there is one.
     */
    fun topUpStrategics() {
        val civs = regions.size
        val taken = tileMap.startingLocationsByNation.values.flatten().toSet()
        val free = tileMap.values.filter { it !in taken && it.resource == null && it.naturalWonder == null }.shuffled(rng)
        val hills = free.filter { !it.isWater && hasHill(it) && it.baseTerrain != Constants.snow }
        val land = free.filter {
            !it.isWater && !hasHill(it) && it.baseTerrain != Constants.mountain &&
                (features(it).isEmpty() || Constants.jungle in features(it) || Constants.forest in features(it))
        }
        val plains = free.filter { !it.isWater && !hasHill(it) && features(it).isEmpty() && it.baseTerrain == Constants.plains }
        val dryGrass = free.filter { !it.isWater && !hasHill(it) && features(it).isEmpty() && it.baseTerrain == Constants.grassland && !it.isAdjacentTo(Constants.freshWater) }

        fun deposit(name: String, list: List<Tile>) {
            val resource = resource(name) ?: return
            val fitting = list.filter { canHold(resource, it) }
            val tile = fitting.firstOrNull { !tileData[it]!!.impacts.containsKey(ImpactType.Strategic) }
                ?: fitting.minByOrNull { tileData[it]!!.impacts[ImpactType.Strategic] ?: 0 } ?: return
            tile.setTileResource(resource, majorDeposit = true)
            tileData.placeImpact(ImpactType.Strategic, tile, 0)
        }
        if (unitsOf("Iron") < 8) deposit("Iron", hills)
        if (unitsOf("Iron") < 4 * civs) deposit("Iron", land)
        if (unitsOf("Horses") < 4 * civs) deposit("Horses", plains)
        if (unitsOf("Horses") < 4 * civs) deposit("Horses", dryGrass)
        if (unitsOf("Coal") < 8) deposit("Coal", hills)
        if (unitsOf("Coal") < 4 * civs) deposit("Coal", land)
        if (unitsOf("Oil") < 4 * civs) deposit("Oil", land)
        if (unitsOf("Aluminum") < 4 * civs) deposit("Aluminum", hills)
        if (unitsOf("Uranium") < 2 * civs) deposit("Uranium", land)
    }

    // endregion

    /** FixSugarJungles: sugar cannot be drawn on jungle, so the jungle becomes marsh on grass */
    fun fixSugarJungles() {
        for (tile in tileMap.values) {
            if (tile.resource != "Sugar" || Constants.jungle !in tile.terrainFeatures) continue
            tile.baseTerrain = Constants.grassland
            tile.setTerrainFeatures(listOf("Marsh"))
            tile.setTerrainTransients()
        }
    }
}
