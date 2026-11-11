package com.unciv.logic.map.mapgenerator.resourceplacement

import com.unciv.Constants
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.CivVWorldSize
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 *  The luxuries of AssignStartingPlots.lua (AssignLuxuryRoles, AssignLuxuryToRegion, PlaceLuxuries, PlaceMarble and the plot lists they use),
 *  for rulesets that set `luxuryPlacementLikeCivV`. It names the luxuries of Civ V: the lists a luxury is placed on, the
 *  fallback weights and the water luxuries are tables of the original. The weights of the regions and of the city states are the
 *  rules `Generated with weight [n] <in [type] Regions>` and `Generated near City States with weight [n]` of the resources.
 *
 *  Roles: the regions in the order of their type (tundra, jungle, forest, desert, hills, plains, grass, the rest), each type shuffled, pick
 *  a luxury by weight / (1 + times picked) that is picked by fewer than 1, 2 or 3 regions (up to 8 civs, up to 12, more) and, as a ninth
 *  type, not new; whales, pearls and crab need a start by the sea and 12 coast tiles in the region (not whales in jungle, pearls in tundra, crab in desert
 *  regions). Three city state luxuries are drawn from the rest, marble is special, of what is left a number by world size
 *  (1 on Huge ... 11 on Duel) is left out and the others go anywhere.
 *  Placement: each civ's start gets 1 (2 for a legendary start) + 1 if its region is poor per tile of its rectangle + 1 if poor per land tile,
 *  each city state gets one, the regions get their share by civ count and world size, the random types a count by world size, a second type
 *  at the starts, and marble.
 */
class CivVLuxuryPlacement(
    private val regions: List<Region>,
    private val tileMap: TileMap,
    private val tileData: TileDataMap,
    private val ruleset: Ruleset,
    private val rng: Random
) {
    companion object {
        /** Lua region type numbers, 1 to 8; the number of a region of another type is 0 and comes last */
        private val regionTypeOrder = listOf("Tundra", "Jungle", "Forest", "Desert", "Hill", "Plains", "Grassland", "Hybrid")

        /** luxury_fallback_weights: also the list of the luxuries that are assigned or left out */
        private val fallbackWeights = linkedMapOf(
            "Whales" to 10, "Pearls" to 10, "Gold Ore" to 10, "Silver" to 5, "Gems" to 10, "Ivory" to 5, "Furs" to 10, "Dyes" to 5,
            "Spices" to 5, "Silk" to 5, "Sugar" to 5, "Cotton" to 5, "Wine" to 5, "Incense" to 5, "Copper" to 5, "Salt" to 5,
            "Citrus" to 5, "Truffles" to 5, "Cocoa" to 5, "Crab" to 10
        )
        private const val MARBLE = "Marble"
        private val waterLuxuries = setOf("Whales", "Pearls", "Crab")

        /** GetIndicesForLuxuryType: the numbers (1-15) of the plot lists a luxury goes on, best first */
        private val listsOf = mapOf(
            "Whales" to listOf(1), "Pearls" to listOf(1), "Crab" to listOf(1),
            "Gold Ore" to listOf(4, 10, 5), "Silver" to listOf(4, 5, 14, 12), "Gems" to listOf(6, 7, 4, 8),
            MARBLE to listOf(12, 10, 11, 4), "Ivory" to listOf(11, 12), "Furs" to listOf(14, 15), "Dyes" to listOf(9, 8, 2),
            "Spices" to listOf(8, 15, 2), "Silk" to listOf(15, 8), "Sugar" to listOf(2, 8, 3, 13), "Cotton" to listOf(3, 13, 12),
            "Wine" to listOf(11, 12, 13), "Incense" to listOf(10, 3, 11), "Copper" to listOf(4, 5, 12, 14),
            "Salt" to listOf(11, 10, 14, 9), "Citrus" to listOf(8, 6, 15, 3), "Truffles" to listOf(15, 8, 2, 5),
            "Cocoa" to listOf(8, 6, 15)
        )

        /** GetRegionLuxuryTargetNumbers, by number of civs (index 1 = 1 civ) */
        private val regionTargets = mapOf(
            CivVWorldSize.Duel to List(22) { 1 },
            CivVWorldSize.Tiny to listOf(0, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            CivVWorldSize.Small to listOf(0, 3, 3, 3, 4, 4, 4, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            CivVWorldSize.Standard to listOf(0, 3, 3, 4, 4, 5, 5, 6, 5, 4, 4, 3, 3, 2, 2, 1, 1, 1, 1, 1, 1, 1),
            CivVWorldSize.Large to listOf(0, 3, 4, 4, 5, 5, 5, 6, 6, 7, 6, 5, 5, 4, 4, 3, 3, 2, 2, 2, 2, 2),
            CivVWorldSize.Huge to listOf(0, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 8, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2)
        )

        /** GetWorldLuxuryTargetNumbers: total luxuries of the random types, and the loop target, for sparse / standard / abundant */
        private fun worldTargets(size: CivVWorldSize, setting: Int): Pair<Int, Int> {
            val table = when (setting) {
                1 -> listOf(14 to 3, 24 to 4, 36 to 4, 48 to 5, 60 to 5, 76 to 6)
                3 -> listOf(24 to 3, 40 to 4, 60 to 4, 80 to 5, 100 to 5, 128 to 6)
                else -> listOf(18 to 3, 30 to 4, 45 to 4, 60 to 5, 75 to 5, 95 to 6)
            }
            return table[size.ordinal]
        }

        private val disabledByWorldSize = mapOf(
            CivVWorldSize.Duel to 11, CivVWorldSize.Tiny to 8, CivVWorldSize.Small to 6,
            CivVWorldSize.Standard to 4, CivVWorldSize.Large to 2, CivVWorldSize.Huge to 1
        )

        private val randomRatios = listOf(
            listOf(1.0), listOf(0.55, 0.45), listOf(0.40, 0.33, 0.27), listOf(0.35, 0.25, 0.25, 0.15),
            listOf(0.25, 0.25, 0.20, 0.15, 0.15), listOf(0.20, 0.20, 0.20, 0.15, 0.15, 0.10),
            listOf(0.20, 0.20, 0.15, 0.15, 0.10, 0.10, 0.10), listOf(0.20, 0.15, 0.15, 0.10, 0.10, 0.10, 0.10, 0.10)
        )
    }

    /** What AssignLuxuryRoles decided */
    class Roles(
        val cityStateLuxuries: List<String>,
        val randomLuxuries: List<String>,
        val regionOrder: List<Region>
    )

    private val resourceSetting = when {
        tileMap.mapParameters.getLegendaryStart() -> 4
        tileMap.mapParameters.getStrategicBalance() -> 5
        tileMap.mapParameters.getMapResources() == MapResourceSetting.sparse -> 1
        tileMap.mapParameters.getMapResources() == MapResourceSetting.abundant -> 3
        else -> 2
    }
    private val worldSize = CivVWorldSize.forTiles(tileMap.values.size)
    private val luxuryCount = HashMap<String, Int>()
    private val assignedToRegions = ArrayList<String>()
    private val lowFertilityOfLuxury = HashMap<String, Int>()
    private val lowFertilityOfRegion = HashMap<Region, Int>()
    private var totalPlaced = 0
    private val marbleLayer = HashSet<Tile>()
    private lateinit var roles: Roles

    private fun resource(name: String) = ruleset.tileResources[name]

    private fun weightIn(resource: TileResource, region: Region): Float? =
        resource.getMatchingUniques(UniqueType.ResourceWeighting, GameContext(region = region)).firstOrNull()?.params?.get(0)?.toFloat()

    private fun <T> pickWeighted(options: List<Pair<T, Double>>): T? {
        val total = options.sumOf { it.second }
        if (options.isEmpty() || total <= 0.0) return null
        var roll = rng.nextDouble() * total
        for ((item, weight) in options) {
            roll -= weight
            if (roll < 0) return item
        }
        return options.last().first
    }

    private fun coastTiles(region: Region) = tileMap.getTilesInRectangle(region.rect).count { it.isWater && it.baseTerrain == Constants.coast }

    private fun startIsByTheSea(region: Region) = tileMap[region.startPosition!!].isAdjacentToCoast()

    private fun splitCap() = when {
        regions.size > 12 -> 3
        regions.size > 8 -> 2
        else -> 1
    }

    // region: AssignLuxuryRoles

    fun assignRoles(): Roles {
        val order = regions.groupBy { regionTypeOrder.indexOf(it.type).let { i -> if (i < 0) 99 else i } }
            .toSortedMap().values.flatMap { it.shuffled(rng) }

        val assignable = ruleset.tileResources.values.filter {
            it.resourceType == ResourceType.Luxury && it.name != MARBLE &&
                !it.hasUnique(UniqueType.LuxurySpecialPlacement) && !it.hasUnique(UniqueType.CityStateOnlyResource) &&
                it.name in fallbackWeights
        }
        for (luxury in assignable) luxuryCount[luxury.name] = 0

        for (region in order) {
            val pick = pickRegionLuxury(region, assignable) ?: continue
            region.luxury = pick
            luxuryCount[pick] = luxuryCount[pick]!! + 1
            if (pick !in assignedToRegions) assignedToRegions.add(pick)
        }

        // Three of the luxuries no region has go to the city states
        val csPool = assignable.filter { it.name !in assignedToRegions && it.hasUnique(UniqueType.LuxuryWeightingForCityStates) }
            .map { it to (it.getMatchingUniques(UniqueType.LuxuryWeightingForCityStates).first().params[0].toDouble()) }.toMutableList()
        val cityStateLuxuries = ArrayList<String>()
        repeat(3) {
            val choice = pickWeighted(csPool) ?: return@repeat
            cityStateLuxuries.add(choice.name)
            csPool.removeAll { it.first == choice }
        }

        // What is left (marble is special): some are not used, the others are placed anywhere
        val unassigned = fallbackWeights.keys.count { it in ruleset.tileResources } - assignedToRegions.size - cityStateLuxuries.size
        val toDisable = min(unassigned, disabledByWorldSize[worldSize]!!)
        val remaining = fallbackWeights.keys.filter { it in ruleset.tileResources && it !in assignedToRegions && it !in cityStateLuxuries }
            .shuffled(rng)
        val random = remaining.drop(toDisable)
        roles = Roles(cityStateLuxuries, random, order)
        return roles
    }

    private fun pickRegionLuxury(region: Region, assignable: List<TileResource>): String? {
        val cap = splitCap()
        fun typeAllowed(name: String) = assignedToRegions.size < 8 || name in assignedToRegions

        fun waterAllowed(name: String, withCrab: Boolean): Boolean {
            if (name !in waterLuxuries) return true
            if (name == "Crab" && !withCrab) return true
            if (name == "Whales" && region.type == "Jungle") return false
            if (name == "Pearls" && region.type == "Tundra") return false
            if (name == "Crab" && region.type == "Desert") return false
            return startIsByTheSea(region) && coastTiles(region) >= 12
        }

        // The weights of the type of the region
        var options = assignable.filter { luxuryCount[it.name]!! < cap && typeAllowed(it.name) && waterAllowed(it.name, true) }
            .mapNotNull { r -> weightIn(r, region)?.let { r.name to it / (1.0 + luxuryCount[r.name]!!) } }
        // The fallback weights, up to 3 regions each
        if (options.isEmpty()) options = fallbackWeights.filter { (name, _) ->
            name in ruleset.tileResources && luxuryCount[name]!! < 3 && typeAllowed(name) && waterAllowed(name, false)
        }.map { (name, weight) -> name to weight / (1.0 + luxuryCount[name]!!) }
        if (options.isEmpty()) options = fallbackWeights.filter { (name, _) -> name in ruleset.tileResources && luxuryCount[name]!! < 3 && typeAllowed(name) }
            .map { (name, weight) -> name to weight / (1.0 + luxuryCount[name]!!) }
        return pickWeighted(options)
    }

    // endregion

    // region: the plot lists

    /** The numbers 1 to 15 of the lists of the original that [tile] is on (a tile can be on several), 0 for the marble list is returned apart */
    private fun listsOfTile(tile: Tile, region: Boolean, regionLand: Set<Int>? = null): List<Int> {
        if (tile.naturalWonder != null) return emptyList()
        if (tile.isWater) {
            if (tile.baseTerrain != Constants.coast || "Atoll" in tile.terrainFeatures || Constants.ice in tile.terrainFeatures) return emptyList()
            // Next to land (the region lists: next to the landmass of the region)
            val next = tile.neighbors.filter { !it.isWater }
            val ok = if (regionLand == null) next.any() else next.any { it.getContinent() in regionLand }
            return if (ok) listOf(1) else emptyList()
        }
        if (tile.baseTerrain == Constants.mountain) return emptyList()
        val terrain = tile.baseTerrain
        val features = tile.terrainFeatures.filter { it != Constants.hill }
        if (Constants.hill in tile.terrainFeatures) {
            if (terrain == Constants.snow) return emptyList()
            return when (features.singleOrNull()) {
                null -> if (features.isEmpty()) listOf(4) else emptyList()
                Constants.jungle -> listOf(6, 5)
                Constants.forest -> listOf(7, 5)
                else -> emptyList()
            }
        }
        val fresh = tile.isAdjacentTo(Constants.freshWater)
        return when {
            features.isEmpty() -> when (terrain) {
                Constants.tundra -> listOf(14)
                Constants.desert -> listOf(10)
                Constants.plains -> listOf(11)
                Constants.grassland -> if (fresh) listOf(13) else listOf(12)
                else -> emptyList()
            }
            features.first() == "Marsh" -> listOf(2)
            features.first() == "Flood plains" -> listOf(3)
            features.first() == Constants.jungle -> listOf(8)
            features.first() == Constants.forest -> if (terrain == Constants.tundra) listOf(9, 14) else listOf(9, 15)
            else -> emptyList()
        }
    }

    private fun isMarbleSite(tile: Tile): Boolean {
        if (tile.isWater || tile.naturalWonder != null || tile.baseTerrain == Constants.mountain) return false
        val features = tile.terrainFeatures.filter { it != Constants.hill }
        if (Constants.hill in tile.terrainFeatures) return features.isEmpty() && tile.baseTerrain != Constants.snow
        if (features.isNotEmpty()) return false
        return when (tile.baseTerrain) {
            Constants.tundra, Constants.desert -> true
            Constants.plains -> !tile.isAdjacentTo(Constants.freshWater)
            Constants.grassland -> !tile.isAdjacentTo(Constants.freshWater)
            else -> false
        }
    }

    /** GenerateLuxuryPlotListsAtCitySite: the lists of the tiles within [radius] of [site] */
    private fun listsAtSite(site: Tile, radius: Int): Array<List<Tile>> {
        val lists = Array(16) { ArrayList<Tile>() }
        for (ring in 1..radius) for (tile in site.getTilesAtDistance(ring)) for (n in listsOfTile(tile, false)) lists[n].add(tile)
        return Array(16) { lists[it] }
    }

    /** GenerateLuxuryPlotListsInRegion: all the tiles of the region's rectangle on its landmass */
    private fun listsInRegion(region: Region): Array<List<Tile>> {
        val lists = Array(16) { ArrayList<Tile>() }
        val landmasses = setOfNotNull(region.continentID.takeIf { it != -1 })
        for (tile in tileMap.getTilesInRectangle(region.rect)) {
            if (!tile.isWater && region.continentID != -1 && tile.getContinent() != region.continentID) continue
            for (n in listsOfTile(tile, true, if (landmasses.isEmpty()) null else landmasses)) lists[n].add(tile)
        }
        return Array(16) { lists[it] }
    }

    private lateinit var globalLists: Array<List<Tile>>
    private lateinit var marbleList: List<Tile>

    /** GenerateGlobalResourcePlotLists (the luxury part): not on a start or city state, no resource yet */
    private fun buildGlobalLists() {
        val taken = tileMap.startingLocationsByNation.values.flatten().toSet()
        val lists = Array(16) { ArrayList<Tile>() }
        val marble = ArrayList<Tile>()
        for (tile in tileMap.values.shuffled(rng)) {
            if (tile in taken || tile.resource != null) continue
            for (n in listsOfTile(tile, false)) lists[n].add(tile)
            if (isMarbleSite(tile)) marble.add(tile)
        }
        globalLists = Array(16) { lists[it] }
        marbleList = marble
    }

    // endregion

    // region: PlaceSpecificNumberOfResources

    private fun canHold(resource: TileResource, tile: Tile) =
        tile.resource == null && tile.naturalWonder == null &&
            resource.terrainsCanBeFoundOn.any { it == tile.baseTerrain || it in tile.terrainFeatures }

    /**
     *  PlaceSpecificNumberOfResources: puts [amount] (at most [ratio] x the list's size) of [resource] on the first tiles of [list]
     *  that are free (and have no luxury impact when [checkImpact]), each giving an impact of [minRadius] to [maxRadius].
     *  @return how many of [amount] are not placed
     */
    private fun place(resource: TileResource, amount: Int, ratio: Float, checkImpact: Boolean, minRadius: Int, maxRadius: Int, list: List<Tile>): Int {
        var left = amount
        val toPlace = min(amount, ceil(ratio * list.size).toInt())
        repeat(toPlace) {
            val tile = list.firstOrNull {
                canHold(resource, it) && (!checkImpact || !tileData[it]!!.impacts.containsKey(ImpactType.Luxury))
            } ?: return@repeat
            tile.setTileResource(resource, majorDeposit = false)
            totalPlaced++
            left--
            if (checkImpact) {
                val radius = minRadius + if (maxRadius > minRadius) rng.nextInt(1 + maxRadius - minRadius) else 0
                tileData.placeImpact(ImpactType.Luxury, tile, radius)
            }
        }
        return left
    }

    /** Down the lists of a luxury, as the original does: one call of PlaceSpecificNumberOfResources for each list, until none is left.
     *  [ratios] and [radii] (the impact of each placed one) are those of the call for the first, second ... list (the last one repeats) */
    private fun placeDownTheLists(
        name: String, amount: Int, lists: Array<List<Tile>>, checkImpact: Boolean, ratios: List<Float>, radii: List<IntRange> = listOf(0..0)
    ): Int {
        val resource = resource(name) ?: return amount
        val indices = listsOf[name] ?: return amount
        var left = amount
        for ((i, index) in indices.withIndex()) {
            if (left <= 0) break
            val radius = radii[min(i, radii.size - 1)]
            left = place(resource, left, ratios[min(i, ratios.size - 1)], checkImpact, radius.first, radius.last, lists[index].shuffled(rng))
        }
        return left
    }

    // endregion

    // region: PlaceLuxuries

    fun placeLuxuries() {
        buildGlobalLists()
        // The marble layer: no marble within 3 of a city state (and later 6 of another marble)
        for (start in tileMap.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.values.flatten())
            for (ring in 0..3) start.getTilesAtDistance(ring).forEach { marbleLayer.add(it) }
        val order = roles.regionOrder.filter { it.luxury != null }
        val averageFertilityPerRectangle = { r: Region -> r.totalFertility.toDouble() / (r.rect.width * r.rect.height).coerceAtLeast(1f) }

        // 1. The civs' starts
        for (region in order) {
            val name = region.luxury!!
            var toPlace = if (resourceSetting == 4) 2 else 1
            if (averageFertilityPerRectangle(region) < 2.5) {
                toPlace++
                lowFertilityOfLuxury.merge(name, 1, Int::plus)
                lowFertilityOfRegion.merge(region, 1, Int::plus)
            }
            val landPlots = region.tiles.count { it.baseTerrain != Constants.mountain }.coerceAtLeast(1)
            if (region.totalFertility.toDouble() / landPlots < 4) {
                toPlace++
                lowFertilityOfLuxury.merge(name, 1, Int::plus)
                lowFertilityOfRegion.merge(region, 1, Int::plus)
            }
            val site = tileMap[region.startPosition!!]
            var left = placeDownTheLists(name, toPlace, listsAtSite(site, 2), false, listOf(0.5f))
            if (left > 0) left = placeDownTheLists(name, left, listsAtSite(site, 3), false, listOf(1f))
            if (left > 0) {
                // The extras are not made up for, but a random type takes the place of one
                lowFertilityOfLuxury.merge(name, -left, Int::plus)
                lowFertilityOfRegion.merge(region, -left, Int::plus)
            }
            if (left > 0 && roles.randomLuxuries.isNotEmpty()) {
                val lists = listsAtSite(site, 3)
                var remaining = 1
                for (random in roles.randomLuxuries) {
                    if (remaining > 0) remaining = placeDownTheLists(random, 1, lists, false, listOf(1f))
                }
            }
        }

        // 2. The city states
        for (start in tileMap.startingLocationsByNation.filterKeys { ruleset.nations[it]!!.isCityState }.map { it.value.first() })
            placeCityStateLuxury(start)

        // 3. The regions' own luxury
        val numCivs = regions.size
        val targets = regionTargets[worldSize]!!
        for (region in regions) {
            val name = region.luxury ?: continue
            val split = luxuryCount[name]!!.coerceAtLeast(1)
            var target = floor((targets[min(numCivs, targets.size) - 1] + 0.5 * (lowFertilityOfLuxury[name] ?: 0)) / split).toInt()
            target -= lowFertilityOfRegion[region] ?: 0
            if (resourceSetting == 1) target-- else if (resourceSetting == 3) target++
            val toPlace = max(1, target)
            placeDownTheLists(name, toPlace, listsInRegion(region), true, listOf(0.3f, 0.3f, 0.4f, 0.5f), listOf(0..3, 0..3, 0..2, 0..2))
        }

        // 4. The random types, anywhere
        if (roles.randomLuxuries.isNotEmpty()) placeRandomLuxuries()

        // 5. A second luxury at each start (not on sparse)
        if (resourceSetting != 1) for (region in regions) placeSecondLuxury(region)

        // 6. Marble
        if (MARBLE in ruleset.tileResources) placeMarble()
    }

    private fun placeCityStateLuxury(start: Tile) {
        val region = regions.firstOrNull { r -> r.assignedMinorCivs.any { civ -> start in (tileMap.startingLocationsByNation[civ.civName] ?: emptySet()) } }
        val allowed = allowedAtSite(start, 2)
        val options = ArrayList<Pair<String, Double>>()
        val csOnly = roles.cityStateLuxuries.filter { it in allowed }
        for (name in csOnly) options.add(name to 75.0 / csOnly.size)
        if (roles.randomLuxuries.isNotEmpty() || region != null) {
            val randoms = roles.randomLuxuries.filter { it in allowed }
            val count = randoms.size + if (region != null) 1 else 0
            val regional = region?.luxury
            if (regional != null && regional in allowed) options.add(regional to 25.0 / count)
            for (name in randoms) options.add(name to 25.0 / count)
        }
        val choice = pickWeighted(options) ?: return
        placeDownTheLists(choice, 1, listsAtSite(start, 2), false, listOf(1f))
    }

    /** GetListOfAllowableLuxuriesAtCitySite: what the tiles around a site can hold */
    private fun allowedAtSite(site: Tile, radius: Int): Set<String> {
        val allowed = HashSet<String>()
        for (ring in 1..radius) for (tile in site.getTilesAtDistance(ring)) {
            if (tile.isWater) {
                if (tile.baseTerrain == Constants.coast && "Atoll" !in tile.terrainFeatures && Constants.ice !in tile.terrainFeatures) allowed += listOf("Whales", "Pearls")
                continue
            }
            val terrain = tile.baseTerrain
            val features = tile.terrainFeatures.filter { it != Constants.hill }
            if (Constants.hill in tile.terrainFeatures && terrain != Constants.snow) {
                allowed += listOf("Gold Ore", "Silver", "Gems")
                if (features.isEmpty()) allowed += MARBLE
            } else if (tile.baseTerrain != Constants.mountain) {
                if (features.isEmpty()) when (terrain) {
                    Constants.tundra -> allowed += listOf("Furs", "Silver", MARBLE)
                    Constants.desert -> allowed += listOf("Gold Ore", MARBLE, "Incense")
                    Constants.plains -> allowed += listOf(MARBLE, "Ivory", "Wine", "Incense")
                    Constants.grassland -> allowed += if (tile.isAdjacentTo(Constants.freshWater)) listOf("Sugar", "Cotton", "Wine")
                        else listOf(MARBLE, "Ivory", "Cotton", "Wine")
                } else when (features.first()) {
                    "Marsh" -> allowed += listOf("Dyes", "Sugar")
                    "Flood plains" -> allowed += listOf("Cotton", "Incense")
                    Constants.jungle -> allowed += listOf("Gems", "Dyes", "Spices", "Silk", "Sugar", "Cocoa")
                    Constants.forest -> {
                        allowed += listOf("Furs", "Dyes")
                        if (terrain == Constants.tundra) allowed += "Silver" else allowed += listOf("Spices", "Silk")
                    }
                }
            }
        }
        return allowed
    }

    private fun placeRandomLuxuries() {
        val random = roles.randomLuxuries
        val (worldTarget, loopTarget) = worldTargets(worldSize, resourceSetting)
        val target = worldTarget + rng.nextInt(max(1, regions.size)) - totalPlaced
        for ((loop, name) in random.withIndex()) {
            val toPlace = when {
                random.size * 3 > target -> 3
                random.size > 8 -> max(3, ceil(target / 10.0).toInt())
                else -> {
                    val minimum = max(3, loopTarget - (loop + 1))
                    val share = ceil(target * randomRatios[random.size - 1][loop]).toInt()
                    max(minimum, share)
                }
            }
            val resource = resource(name) ?: continue
            var left = toPlace
            for ((i, index) in (listsOf[name] ?: continue).withIndex()) {
                if (left <= 0) break
                left = place(resource, left, if (i == 3) 0.3f else 0.25f, true, 4, 6, globalLists[index])
            }
        }
    }

    private fun placeSecondLuxury(region: Region) {
        val site = tileMap[region.startPosition!!]
        val allowed = allowedAtSite(site, 2)
        var candidates = roles.randomLuxuries.filter { it in allowed }
        if (resourceSetting != 5) candidates += listOf(MARBLE).filter { it in allowed && it in ruleset.tileResources }
        if (candidates.isEmpty()) candidates = roles.cityStateLuxuries.filter { it in allowed }
        if (candidates.isEmpty()) candidates = regions.mapNotNull { it.luxury }.distinct().filter { it != region.luxury && it in allowed }
        val choice = candidates.randomOrNull(rng) ?: return
        placeDownTheLists(choice, 1, listsAtSite(site, 2), false, listOf(1f))
    }

    /** PlaceMarble */
    private fun placeMarble() {
        val resource = resource(MARBLE) ?: return
        val placed = tileMap.values.count { it.resource == MARBLE }
        val target = ceil(regions.size * when (resourceSetting) { 1 -> 0.5; 3 -> 0.9; else -> 0.75 }).toInt()
        val toPlace = max(2, target - placed)
        if (marbleList.isEmpty()) return
        repeat(toPlace) {
            val tile = marbleList.firstOrNull {
                it !in marbleLayer && !tileData[it]!!.impacts.containsKey(ImpactType.Luxury) && canHold(resource, it)
            } ?: return@repeat
            tile.setTileResource(resource, majorDeposit = false)
            totalPlaced++
            tileData.placeImpact(ImpactType.Luxury, tile, 1)
            for (ring in 1..6) tile.getTilesAtDistance(ring).forEach { marbleLayer.add(it) }
            marbleLayer.add(tile)
        }
    }

    // endregion
}
