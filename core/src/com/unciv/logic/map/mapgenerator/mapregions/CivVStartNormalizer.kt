package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.Constants
import com.unciv.logic.map.mapgenerator.resourceplacement.MapRegionResources
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import kotlin.random.Random

/**
 *  The start normalization of the original (AssignStartingPlots:NormalizeStartLocation, NormalizeCityState and the
 *  AttemptToPlace... functions it calls), for rulesets that set `startNormalizerLikeCivV`.
 *
 *  The original names the terrains and resources of Civ V, so this only works on rulesets with their names.
 *  Every tile of the first two rings is put in a class (four, three or two food, hills, forest, flat with a hammer,
 *  can have a bonus resource) by terrain, feature and whether it is next to fresh water; the classes decide how many
 *  bonus resources the start needs and whether a hill or a horse/iron is added.
 *  Not ported: the results table the original keeps for its region priorities ([startLocationConditions]),
 *  which nothing here reads, and the count of tiles off the map, which nothing reads either.
 */
internal object CivVStartNormalizer {

    private const val LAKES = "Lakes"
    private const val MARSH = "Marsh"
    private const val OASIS = "Oasis"
    private const val FLOOD_PLAINS = "Flood plains"

    /** What one ring of the start looks like. Fields are named as the original's `inner...` and `outer...` variables. */
    class Ring {
        var fourFood = 0
        var threeFood = 0
        var twoFood = 0
        var hills = 0
        var forest = 0
        var oneHammer = 0
        var canHaveBonus = 0
        var nativeTwoFood = 0
    }

    /** The counters that are not per ring */
    class Totals {
        var grass = 0
        var plains = 0
    }

    fun normalizeMajor(startTile: Tile, ruleset: Ruleset, tileData: TileDataMap, legendaryStart: Boolean, strategicBalance: Boolean) {
        val rng = GameContext(tile = startTile).stateBasedRandom("CivVStartNormalizer.normalizeMajor")
        val inner = Ring()
        val outer = Ring()
        val totals = Totals()
        for (tile in startTile.getTilesAtDistance(1)) scan(tile, inner, inner, totals, false)
        for (tile in startTile.getTilesAtDistance(2)) scan(tile, outer, inner, totals, false)

        var innerHammerScore = 4 * inner.hills + 2 * inner.forest + inner.oneHammer
        val outerHammerScore = 2 * outer.hills + outer.forest + outer.oneHammer
        val earlyHammerScore = 2 * inner.forest + outer.forest + inner.oneHammer + outer.oneHammer

        // Change a first ring plot to hills
        if ((outerHammerScore < 8 && innerHammerScore < 2) || innerHammerScore == 0) {
            if (startTile.getTilesAtDistance(1).toList().shuffled(rng).any { placeHills(it) })
                innerHammerScore += 4
        }

        if (strategicBalance) addStrategicBalanceResources(startTile, ruleset, rng)

        // Add a small horse or iron to the second ring
        if (innerHammerScore < 3 && earlyHammerScore < 6)
            startTile.getTilesAtDistance(2).toList().shuffled(rng).any { placeSmallStrategic(it, ruleset, rng) }

        val innerFoodScore = 4 * inner.fourFood + 2 * inner.threeFood + inner.twoFood
        val outerFoodScore = 4 * outer.fourFood + 2 * outer.threeFood + outer.twoFood
        val totalFoodScore = innerFoodScore + outerFoodScore
        val nativeTwoFoodTiles = inner.nativeTwoFood + outer.nativeTwoFood

        var foodBonusesNeeded = foodBonusesNeeded(totalFoodScore, innerFoodScore, nativeTwoFoodTiles, inner.nativeTwoFood)
        if (legendaryStart) foodBonusesNeeded += 2

        // A start of plains only gets one grassland in the second ring, or more bonuses
        if (nativeTwoFoodTiles == 0 && foodBonusesNeeded < 3) {
            val candidates = startTile.getTilesAtDistance(2).filter {
                it.isLand && it.resource == null && it.baseTerrain == Constants.plains && it.terrainFeatures.isEmpty()
            }.toList()
            if (candidates.isEmpty()) foodBonusesNeeded = 3
            else {
                val converted = candidates.random(rng)
                converted.baseTerrain = Constants.grassland
                converted.setTerrainTransients()
                tileData.placeImpact(MapRegions.ImpactType.Strategic, converted, 0) // Keep it a farm tile
            }
        }

        if (foodBonusesNeeded > 0)
            placeFoodBonuses(startTile, ruleset, rng, foodBonusesNeeded, inner.canHaveBonus, outer.canHaveBonus, legendaryStart, true)

        // Grass starts get stone
        val stoneNeeded = when {
            totals.grass >= 9 && totals.plains == 0 -> 2
            totals.grass >= 6 && totals.plains <= 4 -> 1
            else -> 0
        }
        if (stoneNeeded > 0) placeStone(startTile, ruleset, rng, stoneNeeded)
    }

    fun normalizeCityState(startTile: Tile, ruleset: Ruleset) {
        val rng = GameContext(tile = startTile).stateBasedRandom("CivVStartNormalizer.normalizeCityState")
        val inner = Ring()
        val outer = Ring()
        val totals = Totals()
        for (tile in startTile.getTilesAtDistance(1)) scan(tile, inner, inner, totals, true)
        for (tile in startTile.getTilesAtDistance(2)) scan(tile, outer, inner, totals, true)

        // Only the first ring counts for the hammers, and a hill is added below 4
        if (4 * inner.hills + 2 * inner.forest + inner.oneHammer < 4)
            startTile.getTilesAtDistance(1).toList().shuffled(rng).any { placeHills(it) }

        val innerFoodScore = 4 * inner.fourFood + 2 * inner.threeFood + inner.twoFood
        val outerFoodScore = 4 * outer.fourFood + 2 * outer.threeFood + outer.twoFood
        val totalFoodScore = innerFoodScore + outerFoodScore
        val foodBonusesNeeded = when {
            totalFoodScore < 12 || innerFoodScore < 4 -> 2
            totalFoodScore < 16 && innerFoodScore < 9 -> 1
            else -> 0
        }
        if (foodBonusesNeeded > 0)
            placeFoodBonuses(startTile, ruleset, rng, foodBonusesNeeded, inner.canHaveBonus, outer.canHaveBonus, false, false)
    }

    /** The chain of NormalizeStartLocation. [nativeTwoFirstRing] is the number of tiles with two food of their own in the first ring. */
    private fun foodBonusesNeeded(totalFoodScore: Int, innerFoodScore: Int, nativeTwoFoodTiles: Int, nativeTwoFirstRing: Int) = when {
        totalFoodScore < 4 && innerFoodScore == 0 -> 5
        totalFoodScore < 6 -> 4
        totalFoodScore < 8 -> 3
        totalFoodScore < 12 && innerFoodScore < 5 -> 3
        totalFoodScore < 17 && innerFoodScore < 9 -> 2
        nativeTwoFoodTiles <= 1 -> 2
        totalFoodScore < 24 && innerFoodScore < 11 -> 1
        nativeTwoFoodTiles == 2 || nativeTwoFirstRing == 0 -> 1
        totalFoodScore < 20 -> 1
        else -> 0
    }

    private fun Tile.has(feature: String) = feature in terrainFeatures

    private val Tile.isMountain get() = baseTerrain == Constants.mountain
    private val Tile.isLake get() = baseTerrain == LAKES
    private val Tile.isHills get() = has(Constants.hill)

    /**
     *  The classification of a tile of the first or second ring, the same in NormalizeStartLocation and NormalizeCityState except
     *  where [cityState] is set (it has no oases and does not count jungle, native two food and grass and plains).
     *  [ring] is the ring the tile is in, [inner] the first ring: an oasis in the second ring is counted for the first
     *  (as the original does).
     */
    private fun scan(tile: Tile, ring: Ring, inner: Ring, totals: Totals, cityState: Boolean) {
        val isFirstRing = ring === inner
        val terrain = tile.baseTerrain
        val featureless = tile.terrainFeatures.isEmpty()
        val hasForest = tile.has(Constants.forest)
        val hasJungle = tile.has(Constants.jungle)
        val hasMarsh = tile.has(MARSH)

        if (tile.isMountain) return
        if (tile.isWater) {
            if (tile.has(Constants.ice)) return
            if (tile.isLake) {
                ring.twoFood++
                if (!cityState) ring.nativeTwoFood++
            } else if (isFirstRing || terrain == Constants.coast) {
                ring.canHaveBonus++ // The first ring takes any ocean, the second only coast
            }
            return
        }

        if (!cityState && hasJungle) ring.nativeTwoFood++
        if (tile.isHills) {
            ring.hills++
            when {
                hasJungle -> { ring.twoFood++; ring.canHaveBonus++ }
                hasForest -> ring.canHaveBonus++
                !cityState && terrain == Constants.grassland -> totals.grass++
                !cityState && terrain == Constants.plains -> totals.plains++
            }
            return
        }
        if (!cityState && tile.has(OASIS)) {
            inner.threeFood++
            ring.nativeTwoFood++
            return
        }

        val freshWater = tile.isAdjacentTo(Constants.freshWater)
        when (terrain) {
            Constants.grassland -> {
                if (freshWater) ring.fourFood++ else ring.threeFood++
                if (!cityState) totals.grass++
                if (!hasMarsh) ring.canHaveBonus++
                if (hasForest) ring.forest++
                if (!cityState && featureless) ring.nativeTwoFood++
            }
            Constants.plains -> {
                if (freshWater) ring.threeFood++ else ring.twoFood++
                ring.canHaveBonus++
                if (!cityState) totals.plains++
                if (hasForest) ring.forest++ else ring.oneHammer++
            }
            Constants.tundra -> {
                if (freshWater) {
                    ring.threeFood++
                    ring.canHaveBonus++
                    if (hasForest) ring.forest++
                } else {
                    ring.canHaveBonus++
                    if (hasForest) ring.forest++
                }
            }
            Constants.desert -> {
                if (tile.has(FLOOD_PLAINS)) { // Next to a river by its nature
                    ring.fourFood++
                    ring.canHaveBonus++
                    if (!cityState) ring.nativeTwoFood++
                } else ring.canHaveBonus++ // Can have an oasis
            }
        }
    }

    /** AttemptToPlaceHillsAtPlot: not on water, a river side, a forest or a tile with a resource; a mountain becomes hills too */
    private fun placeHills(tile: Tile): Boolean {
        if (tile.resource != null || tile.isWater || tile.isNaturalWonder() || tile.isAdjacentToRiver() || tile.has(Constants.forest))
            return false
        if (tile.isMountain) {
            tile.baseTerrain = Constants.plains
            tile.setTerrainTransients()
        }
        tile.setTerrainFeatures(listOf(Constants.hill))
        return true
    }

    /** AttemptToPlaceSmallStrategicAtPlot: horses (3 in 4) or iron on grassland and plains, iron elsewhere, on flat tiles without a feature */
    private fun placeSmallStrategic(tile: Tile, ruleset: Ruleset, rng: Random): Boolean {
        if (tile.resource != null || !tile.isLand || tile.isMountain || tile.terrainFeatures.isNotEmpty() || tile.isNaturalWonder())
            return false
        val horses = ruleset.tileResources["Horses"]
        val iron = ruleset.tileResources["Iron"] ?: return false
        val choice = if ((tile.baseTerrain == Constants.grassland || tile.baseTerrain == Constants.plains) && horses != null) {
            if (rng.nextInt(4) == 2) iron else horses
        } else iron
        tile.setTileResource(choice, majorDeposit = false)
        return true
    }

    /** AttemptToPlaceBonusResourceAtPlot: what the tile can carry by its terrain. @return placed, and whether it was an oasis */
    private fun placeBonus(tile: Tile, ruleset: Ruleset, allowOasis: Boolean): Pair<Boolean, Boolean> {
        if (tile.resource != null || tile.isNaturalWonder() || tile.baseTerrain == Constants.snow || tile.has(OASIS))
            return false to false
        fun place(name: String): Pair<Boolean, Boolean> {
            val resource = ruleset.tileResources[name] ?: return false to false
            tile.setTileResource(resource)
            return true to false
        }
        val terrain = tile.baseTerrain
        return when {
            tile.has(Constants.jungle) -> place("Bananas")
            tile.has(Constants.forest) -> place("Deer")
            tile.isHills && tile.terrainFeatures.size == 1 -> place("Sheep")
            tile.isMountain -> false to false
            tile.isWater -> if (terrain == Constants.coast && tile.terrainFeatures.isEmpty()) place("Fish") else false to false
            tile.terrainFeatures.isEmpty() -> when (terrain) {
                Constants.grassland -> place("Cattle")
                Constants.plains -> place("Wheat")
                Constants.tundra -> place("Deer")
                Constants.desert -> when {
                    tile.isAdjacentTo(Constants.freshWater) -> place("Wheat")
                    allowOasis -> {
                        tile.addTerrainFeature(OASIS)
                        true to true
                    }
                    else -> false to false
                }
                else -> false to false
            }
            else -> false to false
        }
    }

    /** The loop of NormalizeStartLocation and NormalizeCityState that places [needed] bonus resources: two (three for a legendary start)
     *  in the first ring, up to five in all in the second, then the third ring (majors only). */
    private fun placeFoodBonuses(
        startTile: Tile, ruleset: Ruleset, rng: Random, needed: Int, innerCanHaveBonusAtStart: Int, outerCanHaveBonusAtStart: Int,
        legendaryStart: Boolean, isMajor: Boolean
    ) {
        var stillNeeded = needed
        var innerCanHaveBonus = innerCanHaveBonusAtStart
        var outerCanHaveBonus = outerCanHaveBonusAtStart
        val firstRing = startTile.getTilesAtDistance(1).toList().shuffled(rng)
        val secondRing = startTile.getTilesAtDistance(2).toList().shuffled(rng)
        val thirdRing = startTile.getTilesAtDistance(3).toList().shuffled(rng)
        var triedAllFirst = false
        var triedAllSecond = false
        var triedAllThird = false
        var allowOasis = true
        var innerPlaced = 0
        var outerPlaced = 0

        /** Tries the tiles in order; true if one got a bonus */
        fun tryRing(ring: List<Tile>): Boolean {
            for (tile in ring) {
                val (placed, oasis) = placeBonus(tile, ruleset, allowOasis)
                if (!placed) continue
                if (oasis) allowOasis = false
                return true
            }
            return false
        }

        val firstRingLimit = if (isMajor && legendaryStart) 3 else 2
        // The original also lets a legendary start place 4 in all, which the 5 for everybody already allows
        val secondRingLimit = if (isMajor) 5 else 4
        while (stillNeeded > 0) {
            if (innerPlaced < firstRingLimit && innerCanHaveBonus > 0 && !triedAllFirst) {
                if (tryRing(firstRing)) {
                    innerPlaced++
                    innerCanHaveBonus--
                    stillNeeded--
                } else triedAllFirst = true
            } else if (innerPlaced + outerPlaced < secondRingLimit && outerCanHaveBonus > 0 && !triedAllSecond) {
                if (tryRing(secondRing)) {
                    outerPlaced++
                    outerCanHaveBonus--
                    stillNeeded--
                } else triedAllSecond = true
            } else if (isMajor && !triedAllThird) {
                if (tryRing(thirdRing)) stillNeeded-- else triedAllThird = true
            } else break
        }
    }

    /** AttemptToPlaceStoneAtGrassPlot in the loop of NormalizeStartLocation: one in the first ring, the rest in the second */
    private fun placeStone(startTile: Tile, ruleset: Ruleset, rng: Random, needed: Int) {
        val stone = ruleset.tileResources["Stone"] ?: return
        fun tryRing(ring: List<Tile>): Boolean {
            val tile = ring.firstOrNull {
                it.resource == null && it.isLand && !it.isNaturalWonder() && it.terrainFeatures.isEmpty() && it.baseTerrain == Constants.grassland
            } ?: return false
            tile.setTileResource(stone)
            return true
        }
        var stillNeeded = needed
        val firstRing = startTile.getTilesAtDistance(1).toList().shuffled(rng)
        val secondRing = startTile.getTilesAtDistance(2).toList().shuffled(rng)
        var innerPlaced = 0
        var triedAllFirst = false
        var triedAllSecond = false
        while (stillNeeded > 0) {
            if (innerPlaced < 1 && !triedAllFirst) {
                if (tryRing(firstRing)) { innerPlaced++; stillNeeded-- } else triedAllFirst = true
            } else if (!triedAllSecond) {
                if (tryRing(secondRing)) stillNeeded-- else triedAllSecond = true
            } else break
        }
    }

    /**
     *  AddStrategicBalanceResources: an iron, a horse and an oil within three rings, where they are likely
     *  (iron on hills, horses on flat grass and plains, oil on tundra, desert, snow and marsh) in the first two rings, otherwise
     *  wherever they would be allowed. Deposits are major deposits.
     */
    private fun addStrategicBalanceResources(startTile: Tile, ruleset: Ruleset, rng: Random) {
        val ironList = ArrayList<Tile>(); val horseList = ArrayList<Tile>(); val oilList = ArrayList<Tile>()
        val ironFallback = ArrayList<Tile>(); val horseFallback = ArrayList<Tile>(); val oilFallback = ArrayList<Tile>()
        for (ring in 1..3) for (tile in startTile.getTilesAtDistance(ring)) {
            if (tile.isNaturalWonder() || tile.isWater || tile.isMountain) continue
            val near = ring < 3
            val terrain = tile.baseTerrain
            if (tile.isHills) {
                (if (near) ironList else ironFallback).add(tile)
                if (terrain != Constants.snow && tile.terrainFeatures.size == 1) horseFallback.add(tile)
            } else if (tile.terrainFeatures.isEmpty()) {
                when (terrain) {
                    Constants.tundra, Constants.desert -> {
                        (if (near) oilList else oilFallback).add(tile)
                        ironFallback.add(tile); horseFallback.add(tile)
                    }
                    Constants.plains, Constants.grassland -> {
                        (if (near) horseList else horseFallback).add(tile)
                        ironFallback.add(tile); oilFallback.add(tile)
                    }
                    Constants.snow -> (if (near) oilList else oilFallback).add(tile)
                }
            } else if (tile.has(MARSH)) {
                (if (near) oilList else oilFallback).add(tile)
                ironFallback.add(tile)
            } else if (tile.has(FLOOD_PLAINS)) {
                horseFallback.add(tile); oilFallback.add(tile)
            } else if (tile.has(Constants.jungle) || tile.has(Constants.forest)) {
                ironFallback.add(tile); oilFallback.add(tile)
            }
        }

        fun place(resource: TileResource?, list: List<Tile>): Boolean {
            if (resource == null) return false
            val tile = list.shuffled(rng).firstOrNull { it.resource == null } ?: return false
            tile.setTileResource(resource, majorDeposit = true)
            return true
        }
        val iron = ruleset.tileResources["Iron"]; val horses = ruleset.tileResources["Horses"]; val oil = ruleset.tileResources["Oil"]
        val placedIron = place(iron, ironList)
        val placedHorse = place(horses, horseList)
        val placedOil = place(oil, oilList)
        if (!placedIron) place(iron, ironFallback)
        if (!placedHorse) place(horses, horseFallback)
        if (!placedOil) place(oil, oilFallback)
    }
}
