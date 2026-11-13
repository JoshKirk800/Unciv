package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.equalsPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters
import yairm210.purity.annotations.Readonly
import com.unciv.logic.map.HexMath
import kotlin.math.ceil
import kotlin.math.min

object MinorCivPlacer {

    /** Assigns [civs] to regions or "uninhabited" land and places them. Depends on
     *  assignLuxuries having been called previously.
     *  Note: can silently fail to place all city states if there is too little room.
     *  Currently our GameStarter fills out with random city states, Civ V behavior is to
     *  forget about the discarded city states entirely. */
    fun placeMinorCivs(regions: List<Region>, tileMap: TileMap, civs: List<Civilization>, tileData: TileDataMap, ruleset: Ruleset) {
        if (civs.isEmpty()) return

        // Some but not all city states are assigned to regions directly. Determine the CS density.
        val unassignedCivs = assignMinorCivsDirectlyToRegions(civs, regions)

        // Some city states are assigned to "uninhabited" continents - unless it's an archipelago type map
        // (Because then every continent will have been assigned to a region anyway)
        val uninhabitedCoastal = ArrayList<Tile>()
        val uninhabitedHinterland = ArrayList<Tile>()
        val civAssignedToUninhabited = ArrayList<Civilization>()
        if (!tileMap.usingArchipelagoRegions()) {
            spreadCityStatesBetweenHabitedAndUninhabited(
                tileMap,
                regions,
                tileData,
                uninhabitedCoastal,
                uninhabitedHinterland,
                civs,
                unassignedCivs,
                civAssignedToUninhabited,
                ruleset
            )
        }

        assignCityStatesToRegionsWithCommonLuxuries(regions, unassignedCivs)
        spreadCityStatesEvenlyBetweenRegions(unassignedCivs, regions)
        assignRemainingCityStatesToWorstFertileRegions(regions, unassignedCivs, ruleset.modOptions.constants.startAssignmentLikeCivV)

        // After we've finished assigning, NOW we actually place them
        placeAssignedMinorCivs(
            civAssignedToUninhabited,
            tileMap,
            uninhabitedCoastal,
            tileData,
            ruleset,
            uninhabitedHinterland,
            regions
        )
    }

    private fun spreadCityStatesBetweenHabitedAndUninhabited(
        tileMap: TileMap,
        regions: List<Region>,
        tileData: TileDataMap,
        uninhabitedCoastal: ArrayList<Tile>,
        uninhabitedHinterland: ArrayList<Tile>,
        civs: List<Civilization>,
        unassignedCivs: MutableList<Civilization>,
        civAssignedToUninhabited: ArrayList<Civilization>,
        ruleset: Ruleset
    ) {
        val uninhabitedContinents = tileMap.continentSizes.filter {
            it.value >= 4 && // Don't bother with tiny islands
                regions.none { region -> region.continentID == it.key }
        }.keys
        var numInhabitedTiles = 0
        var numUninhabitedTiles = 0
        val sitesLikeCivV = ruleset.modOptions.constants.cityStateSitesLikeCivV
        // Go through the entire map to build the data
        for (tile in tileMap.values) {
            if (!canPlaceMinorCiv(tile, tileData, sitesLikeCivV)) continue
            val continent = tile.getContinent()
            if (continent in uninhabitedContinents) {
                if (tile.isAdjacentToCoast())
                    uninhabitedCoastal.add(tile)
                else
                    uninhabitedHinterland.add(tile)
                numUninhabitedTiles++
            } else
                numInhabitedTiles++
        }
        // Determine how many minor civs to put on uninhabited continents.
        val maxByUninhabited =
            if (numInhabitedTiles + numUninhabitedTiles == 0) 0
            else (3 * civs.size * numUninhabitedTiles) / (numInhabitedTiles + numUninhabitedTiles)
        val maxByRatio = (civs.size + 1) / 2
        // AssignCityStatesToRegionsOrToUninhabited only hands out what the regions did not take already
        val targetForUninhabited = if (ruleset.modOptions.constants.startAssignmentLikeCivV)
            minOf(maxByRatio, maxByUninhabited, unassignedCivs.size) else min(maxByRatio, maxByUninhabited)
        // Prefer Coast-bias CS for wilderness slots (often coastal).
        val ordered = unassignedCivs.filter { prefersCoastalStart(it, ruleset) } +
            unassignedCivs.filterNot { prefersCoastalStart(it, ruleset) }
        val civsToAssign = ordered.take(targetForUninhabited)
        unassignedCivs.removeAll(civsToAssign)
        civAssignedToUninhabited.addAll(civsToAssign)
    }

    /** If there are still unassigned minor civs, assign extra ones to regions that share their
     *  luxury type with two others, as compensation. Because starting close to a city state is good??
     */
    private fun assignCityStatesToRegionsWithCommonLuxuries(
        regions: List<Region>,
        unassignedCivs: MutableList<Civilization>
    ) {
        if (unassignedCivs.isEmpty()) return
        val regionsWithCommonLuxuries = regions.filter {
            regions.count { other -> other.luxury == it.luxury } >= 3
        }
        // assign one civ each to regions with common luxuries if there are enough to go around
        if (regionsWithCommonLuxuries.isNotEmpty() &&
            regionsWithCommonLuxuries.size <= unassignedCivs.size
        ) {
            regionsWithCommonLuxuries.forEach {
                val civToAssign = unassignedCivs.first()
                unassignedCivs.remove(civToAssign)
                it.assignedMinorCivs.add(civToAssign)
            }
        }
    }

    /** Add one extra to each region as long as there are enough to go around */
    private fun spreadCityStatesEvenlyBetweenRegions(
        unassignedCivs: MutableList<Civilization>,
        regions: List<Region>
    ) {
        if (unassignedCivs.isEmpty()) return
        while (unassignedCivs.size >= regions.size) {
            regions.forEach {
                val civToAssign = unassignedCivs.first()
                unassignedCivs.remove(civToAssign)
                it.assignedMinorCivs.add(civToAssign)
            }
        }
    }


    /** At this point there is at least for sure less remaining city states than regions
        Sort regions by fertility and put extra city states in the worst ones. */
    private fun assignRemainingCityStatesToWorstFertileRegions(
        regions: List<Region>,
        unassignedCivs: MutableList<Civilization>,
        fertilityPerLandTile: Boolean
    ) {
        if (unassignedCivs.isEmpty()) return
        // Civ V: the regions with the least fertility per land tile, not the least in total
        val worstRegions = if (fertilityPerLandTile)
            regions.sortedBy { region -> region.totalFertility.toFloat() / region.tiles.count { it.isLand }.coerceAtLeast(1) }.take(unassignedCivs.size)
        else regions.sortedBy { it.totalFertility }.take(unassignedCivs.size)
        worstRegions.forEach {
            val civToAssign = unassignedCivs.first()
            unassignedCivs.remove(civToAssign)
            it.assignedMinorCivs.add(civToAssign)
        }
    }

    /** Actually placee the minor civs, after they have been sorted into groups and assigned to regions */
    private fun placeAssignedMinorCivs(
        civAssignedToUninhabited: ArrayList<Civilization>,
        tileMap: TileMap,
        uninhabitedCoastal: ArrayList<Tile>,
        tileData: TileDataMap,
        ruleset: Ruleset,
        uninhabitedHinterland: ArrayList<Tile>,
        regions: List<Region>
    ) {
        // All minor civs are assigned - now place them
        // First place the "uninhabited continent" ones, preferring coastal starts
        tryPlaceMinorCivsInTiles(
            civAssignedToUninhabited, tileMap, uninhabitedCoastal, tileData, ruleset
        )
        tryPlaceMinorCivsInTiles(
            civAssignedToUninhabited, tileMap, uninhabitedHinterland, tileData, ruleset
        )
        // Fallback to a random region for civs that couldn't be placed in the wilderness
        for (unplacedCiv in civAssignedToUninhabited) {
            val rng = unplacedCiv.state.stateBasedRandom("MinorCivPlacer.placeAssignedMinorCivs")
            regions.random(rng).assignedMinorCivs.add(unplacedCiv)
        }

        // Now place the ones assigned to specific regions.
        for (region in regions) {
            // More constrained biases (and coastal) first so they do not lose scarce matching tiles.
            val ordered = region.assignedMinorCivs.sortedWith(
                compareByDescending<Civilization> {
                    it.nation.getStartBias(ruleset, it.getGameContextForStartBias()).size
                }.thenByDescending { prefersCoastalStart(it, ruleset) }
            )
            val unplaced = ordered.filterNot { placeMinorCivInRegionLikeCivV(it, region, tileMap, tileData, ruleset) }
                .toMutableList()
            // Only if nothing in the region was free, even when forced
            tryPlaceMinorCivsInTiles(unplaced, tileMap, region.tiles.toMutableList(), tileData, ruleset)
        }
    }

    /**
     *  AssignStartingPlots:PlaceCityStateInRegion: looks for a place along the outer band of the region first
     *  (the 16.7 % of its longer side, and once that fails for the band of what is left inside it), a coastal tile
     *  before an inland one. The tiles must be free of the impact of any start, then (forced) may be within the
     *  impact but not on a start, then may be on one.
     *  The way the original shrinks the part it looks at is kept: a region that is wider than tall is searched
     *  along its left and right edges but shrinks at the top and bottom, and the other way around.
     *  @return whether a place was found
     */
    private fun placeMinorCivInRegionLikeCivV(
        civ: Civilization, region: Region, tileMap: TileMap, tileData: TileDataMap, ruleset: Ruleset
    ): Boolean {
        val biases = civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())
        val rng = GameContext(gameInfo = tileMap.gameInfo).stateBasedRandom("MinorCivPlacer.placeMinorCivInRegionLikeCivV")
        val sitesLikeCivV = ruleset.modOptions.constants.cityStateSitesLikeCivV

        for ((force, ignoreCollisions) in listOf(false to false, true to false, true to true)) {
            var x = region.rect.x.toInt()
            var y = region.rect.y.toInt()
            var width = region.rect.width.toInt()
            var height = region.rect.height.toInt()
            var reachedMiddle = false
            while (!reachedMiddle) {
                if (width <= 0 || height <= 0) break
                if (width < 4 || height < 4) reachedMiddle = true
                val taller = height > width
                val rowsToCheck = ceil(0.167 * (if (taller) height else width)).toInt()

                val coastal = ArrayList<Tile>()
                val inland = ArrayList<Tile>()
                for (row in y until y + height) {
                    for (column in x until x + width) {
                        val inBand = reachedMiddle ||
                            if (taller) row < y + rowsToCheck || row >= y + height - rowsToCheck
                            else column < x + rowsToCheck || column >= x + width - rowsToCheck
                        if (!inBand) continue
                        val coords = HexMath.getTileCoordsFromColumnRow(column, row)
                        val tile = tileMap.getIfTileExistsOrNull(coords.x.toInt(), coords.y.toInt()) ?: continue
                        if (!canPlaceMinorCivInRegion(tile, region, tileData, force, ignoreCollisions, sitesLikeCivV)) continue
                        if (tile.isAdjacentToCoast()) coastal.add(tile) else inland.add(tile)
                    }
                }
                if (taller) {
                    x += rowsToCheck
                    width -= 2 * rowsToCheck
                } else {
                    y += rowsToCheck
                    height -= 2 * rowsToCheck
                }

                val candidates = sequenceOf(coastal, inland).map { list ->
                    filterTilesByStartBias(list, biases).ifEmpty { list }
                }.firstOrNull { it.isNotEmpty() } ?: continue
                placeMinorCiv(civ, tileMap, candidates.random(rng), tileData, ruleset)
                return true
            }
        }
        return false
    }

    /** AssignStartingPlots:CanPlaceCityStateAt: land of the region's landmass that [canPlaceMinorCiv] allows, free of the impact of another start
     *  (or, when [force]d, free of a start itself). */
    @Readonly
    private fun canPlaceMinorCivInRegion(
        tile: Tile, region: Region, tileData: TileDataMap, force: Boolean, ignoreCollisions: Boolean, sitesLikeCivV: Boolean
    ): Boolean {
        if (region.continentID != -1 && tile.getContinent() != region.continentID) return false
        if (!canPlaceMinorCiv(tile, tileData, sitesLikeCivV)) return false
        val impact = tileData[tile]!!.impacts[MapRegions.ImpactType.MinorCiv] ?: return true
        if (!force) return false
        return ignoreCollisions || impact != 99 // 99 is a start itself
    }

    private fun assignMinorCivsDirectlyToRegions(
        civs: List<Civilization>,
        regions: List<Region>
    ): MutableList<Civilization> {
        val minorCivRatio = civs.size.toFloat() / regions.size
        val minorCivPerRegion = when {
            minorCivRatio > 14f -> 10 // lol
            minorCivRatio > 11f -> 8
            minorCivRatio > 8f -> 6
            minorCivRatio > 5.7f -> 4
            minorCivRatio > 4.35f -> 3
            minorCivRatio > 2.7f -> 2
            minorCivRatio > 1.35f -> 1
            else -> 0
        }
        val rng = GameContext(gameInfo = regions.first().tileMap.gameInfo).stateBasedRandom("MinorCivPlacer.assignMinorCivsDirectlyToRegions")
        val unassignedCivs = civs.shuffled(rng).toMutableList()
        if (minorCivPerRegion > 0) {
            regions.forEach {
                val civsToAssign = unassignedCivs.take(minorCivPerRegion)
                it.assignedMinorCivs.addAll(civsToAssign)
                unassignedCivs.removeAll(civsToAssign)
            }
        }
        return unassignedCivs
    }

    /**
     *  Attempts to place civs from [civsToPlace] in tiles from [tileList]. Assumes that
     *  [tileList] is pre-vetted and only contains habitable land tiles.
     *  Respects start biases (nation field + StartBias uniques on nation / [CityStateType]),
     *  including `Avoid [terrainFilter]`, same idea as [com.unciv.logic.GameStarter] major placement.
     *  Will modify both [civsToPlace] and [tileList] as it goes!
     */
    private fun tryPlaceMinorCivsInTiles(civsToPlace: MutableList<Civilization>, tileMap: TileMap, tileList: MutableList<Tile>, tileData: TileDataMap, ruleset: Ruleset) {
        // More constrained biases (and coastal) first so they do not lose scarce matching tiles.
        val ordered = civsToPlace.sortedWith(
            compareByDescending<Civilization> {
                it.nation.getStartBias(ruleset, it.getGameContextForStartBias()).size
            }.thenByDescending { prefersCoastalStart(it, ruleset) }
        ).toMutableList()
        civsToPlace.clear()
        civsToPlace.addAll(ordered)

        while (tileList.isNotEmpty() && civsToPlace.isNotEmpty()) {
            val civToAdd = civsToPlace.first()
            val preferred = filterTilesByStartBias(
                tileList,
                civToAdd.nation.getStartBias(ruleset, civToAdd.getGameContextForStartBias())
            )
            val pool = preferred.ifEmpty { tileList }

            val rng = GameContext(gameInfo = tileMap.gameInfo).stateBasedRandom("MinorCivPlcer.tryPlaceMinorCivsInTiles")
            val chosenTile = pool.random(rng)
            tileList.remove(chosenTile)
            val data = tileData[chosenTile]!!
            // If the randomly chosen tile is too close to a player or a city state, discard it
            if (data.impacts.containsKey(MapRegions.ImpactType.MinorCiv))
                continue
            // Otherwise, go ahead and place the minor civ
            civsToPlace.remove(civToAdd)
            placeMinorCiv(civToAdd, tileMap, chosenTile, tileData, ruleset)
        }
    }

    /** Prefer tiles matching positive start biases; drop tiles matching `Avoid [filter]`. */
    @Readonly
    fun filterTilesByStartBias(tiles: List<Tile>, startBiases: Collection<String>): List<Tile> {
        var preferred = tiles
        for (startBias in startBiases) {
            preferred = when {
                startBias.equalsPlaceholderText("Avoid []") -> {
                    val tileToAvoid = startBias.getPlaceholderParameters()[0]
                    preferred.filter { tile ->
                        tile.getTilesInDistance(1).none { it.matchesTerrainFilter(tileToAvoid, null) }
                    }
                }
                else -> preferred.filter { tile ->
                    tile.getTilesInDistance(1).any { it.matchesTerrainFilter(startBias, null) }
                }
            }
        }
        return preferred
    }

    /** True when nation or city-state type start bias includes Coast. */
    @Readonly
    fun prefersCoastalStart(civ: Civilization, ruleset: Ruleset): Boolean =
        "Coast" in civ.nation.getStartBias(ruleset, civ.getGameContextForStartBias())

    /** The land a city-state may be put on. [sitesLikeCivV]: land that is no mountain and no snow (AssignStartingPlots:CanPlaceCityStateAt).
     *  Else land that is not undesirable for a start (no snow hills, no desert outside desert regions) and not at the edge of the map. */
    @Readonly
    private fun canPlaceMinorCiv(tile: Tile, tileData: TileDataMap, sitesLikeCivV: Boolean) =
        if (sitesLikeCivV) !tile.isWater && !tile.isImpassible() && tile.baseTerrain != Constants.snow
        else !tile.isWater && !tile.isImpassible() &&
            !tileData[tile]!!.isJunk &&
            tile.getBaseTerrain().getMatchingUniques(UniqueType.HasQuality).none { it.params[0] == "Undesirable" } && // So we don't get snow hills
            tile.neighbors.count() == 6 // Avoid map edges

    private fun placeMinorCiv(civ: Civilization, tileMap: TileMap, tile: Tile, tileData: TileDataMap, ruleset: Ruleset) {
        tileMap.addStartingLocation(civ.civID, tile)
        tileData.placeImpact(MapRegions.ImpactType.MinorCiv,tile, 4)
        tileData.placeImpact(MapRegions.ImpactType.Luxury,  tile, 3)
        tileData.placeImpact(MapRegions.ImpactType.Strategic,tile, 0)
        tileData.placeImpact(MapRegions.ImpactType.Bonus,   tile, 3)

        // As the original does, the city states of the Civ V start normalizer are improved after all resources are placed (see MapRegions)
        if (!ruleset.modOptions.constants.startNormalizerLikeCivV)
            StartNormalizer.normalizeStart(tile, tileMap, tileData, ruleset, isMinorCiv = true)
    }

}
