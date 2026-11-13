package com.unciv.logic.city.managers

import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.ui.components.extensions.toPercent
import com.unciv.utils.withItem
import com.unciv.utils.withoutItem
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class CityExpansionManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var city: City
    var cultureStored: Int = 0

    /** The number of tiles this city gained from its culture (CvCity::m_iJONSCultureLevel): bought tiles and tiles from other sources do not count.
     *  Negative in a saved game from before the counter existed; [setTransients] then derives it from the tiles. */
    var cultureLevel: Int = -1

    /** CvCity::m_iCheapestPlotInfluence: the lowest influence distance of an unowned tile within the expand range at the city's last acquisition of a tile
     *  (plotAcquisitionLikeCivV; it sets the price of a tile). Negative in a saved game from before the counter existed: then it is worked out on demand. */
    var cheapestPlotInfluence = -1

    fun clone(): CityExpansionManager {
        val toReturn = CityExpansionManager()
        toReturn.cultureStored = cultureStored
        toReturn.cultureLevel = cultureLevel
        toReturn.cheapestPlotInfluence = cheapestPlotInfluence
        return toReturn
    }

    @Readonly
    fun tilesClaimed(): Int {
        val tilesAroundCity = city.getCenterTile().neighbors
                .map { it.position }
        return city.tiles.count { it != city.location && it !in tilesAroundCity}
    }

    // The formula of the original is CvCity::GetJONSCultureThreshold (GlobalDefines CULTURE_COST_FIRST_PLOT 15, ..._LATER_PLOT_MULTIPLIER 10, ..._EXPONENT 1.1,
    // CULTURE_PLOT_COST_MOD_MINIMUM -85, CULTURE_COST_VISIBLE_DIVISOR 5): 15 + (10 x level)^1.1, x 1.5 for a city-state, x (100 + the sum of the modifiers, at least -85) %,
    // x the game speed, rounded down to 5. The Civ V rulesets turn it on with the ModConstants cultureCostLikeCivV.
    // Other rulesets keep the older formula: 6 x (claimed tiles + 1.4813)^1.3, which is the Civ VI one ("Mathematics of Civilization V" has 20 + (10 (t-1))^1.1).
    @Readonly
    fun getCultureToNextTile(): Int {
        if (city.getRuleset().modOptions.constants.cultureCostLikeCivV) return getCultureToNextTileLikeCivV()

        var cultureToNextTile = 6 * (max(0, tilesClaimed()) + 1.4813).pow(1.3)

        cultureToNextTile *= city.civ.gameInfo.speed.cultureCostModifier

        if (city.civ.isCityState)
            cultureToNextTile *= 1.5f   // City states grow slower, perhaps 150% cost?

        city.forEachMatchingUnique(UniqueType.BorderGrowthPercentage) { unique ->
            if (city.matchesFilter(unique.params[1]))
                cultureToNextTile *= unique.params[0].toPercent()
        }

        return cultureToNextTile.roundToInt()
    }

    @Readonly
    private fun getCultureToNextTileLikeCivV(): Int {
        val level = if (cultureLevel >= 0) cultureLevel else tilesClaimed()
        var cost = 15 + (10.0 * level).pow(1.1).toInt()
        if (city.civ.isCityState) cost = cost * 150 / 100

        var percent = 0
        city.forEachMatchingUnique(UniqueType.BorderGrowthPercentage) { unique ->
            if (city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        }
        if (percent != 0) cost = cost * (100 + max(percent, -85)) / 100

        cost = (cost * city.civ.gameInfo.speed.cultureCostModifier).toInt()
        if (cost > 10) cost -= cost % 5
        return cost
    }

    @Readonly
    fun canBuyTile(tile: Tile): Boolean {
        return when {
            city.isPuppet || city.isBeingRazed -> false
            tile.getOwner() != null -> false
            city.isInResistance() -> false
            tile.aerialDistanceTo(city.getCenterTile()) > buyRange() -> false
            else -> tile.neighbors.any { it.getCity() == city }
        }
    }

    /** The range within which a tile can be bought: the city's expand range, or MAXIMUM_BUY_PLOT_DISTANCE under plotAcquisitionLikeCivV (CvCity::CanBuyPlot) */
    @Readonly
    private fun buyRange(): Int =
        if (city.getRuleset().modOptions.constants.plotAcquisitionLikeCivV) city.getRuleset().ai.define(AiDefine.MAXIMUM_BUY_PLOT_DISTANCE)
        else city.getExpandRange()

    fun buyTile(tile: Tile) {
        val goldCost = getGoldCostOfTile(tile)

        class TriedToBuyNonContiguousTileException(msg: String) : Exception(msg)
        if (tile.neighbors.none { it.getCity() == city })
            throw TriedToBuyNonContiguousTileException("$city tried to buy $tile, but it owns none of the neighbors")

        class NotEnoughGoldToBuyTileException(msg: String) : Exception(msg)
        if (city.civ.gold < goldCost && !city.civ.gameInfo.gameParameters.godMode)
            throw NotEnoughGoldToBuyTileException("$city tried to buy $tile, but lacks gold (cost $goldCost, has ${city.civ.gold})")

        city.civ.addGold(-goldCost)
        city.civ.plotsBought++ // CvCity::BuyPlot: CvPlayer::ChangeNumPlotsBought
        acquireTile(tile)
        // CvCity::BuyPlot: every foreign city within city radius of the tile notes it (AI_ChangeNumPlotsAcquiredByOtherPlayer)
        for (nearbyTile in tile.getTilesInDistance(3)) {
            val nearbyCity = nearbyTile.getCity() ?: continue
            if (nearbyTile.isCityCenter() && nearbyCity.civ != city.civ) DiplomacyEvents.onPlotBoughtNear(nearbyCity.civ, city.civ, 1)
        }

        // Reapply worked tiles optimization (aka CityFocus) - doing it here means AI profits too
        city.reassignPopulationDeferred()
    }

    @Readonly
    fun getGoldCostOfTile(tile: Tile, extraTiles: Int = 0): Int {
        if (city.getRuleset().modOptions.constants.plotAcquisitionLikeCivV) return getGoldCostOfTileLikeCivV(tile, extraTiles)

        val baseCost = 50
        val distanceFromCenter = tile.aerialDistanceTo(city.getCenterTile())
        var cost = baseCost * (distanceFromCenter - 1) + (tilesClaimed() + extraTiles) * 5.0

        cost *= city.civ.gameInfo.speed.goldCostModifier

        city.forEachMatchingUnique(UniqueType.TileCostPercentage) { unique -> 
            if (city.matchesFilter(unique.params[1]))
                cost *= unique.params[0].toPercent()
        }

        return cost.roundToInt()
    }

    /**
     *  CvCity::GetBuyPlotCost: the civilization's [Civilization.getBuyPlotCost] (with [extraTiles] more tiles bought) times
     *  (PLOT_INFLUENCE_BASE_MULTIPLIER 100 + the influence distance of the tile less the city's cheapest, x PLOT_INFLUENCE_DISTANCE_MULTIPLIER 100 /
     *  PLOT_INFLUENCE_DISTANCE_DIVISOR 3, and PLOT_BUY_RESOURCE_COST -100 for a resource the civilization can see) %, only where that is above 100,
     *  x game speed gold percent, x (100 + the city's own tile cost percentages) %, rounded down to PLOT_COST_APPEARANCE_DIVISOR 5.
     *  9999 for a tile beyond MAXIMUM_BUY_PLOT_DISTANCE. Every division truncates, as the original's integer arithmetic does.
     */
    @Readonly
    private fun getGoldCostOfTileLikeCivV(tile: Tile, extraTiles: Int): Int {
        val ai = city.getRuleset().ai
        val center = city.getCenterTile()
        val maxRange = ai.define(AiDefine.MAXIMUM_BUY_PLOT_DISTANCE)
        if (tile.aerialDistanceTo(center) > maxRange) return 9999

        var cost = city.civ.getBuyPlotCost(extraTiles)
        // A tile the path cannot reach has distance -1, as in the original
        val distance = PlotInfluence.distance(center, tile, maxRange) - effectiveCheapestPlotInfluence()
        var factor = ai.define(AiDefine.PLOT_INFLUENCE_BASE_MULTIPLIER) +
            distance * ai.define(AiDefine.PLOT_INFLUENCE_DISTANCE_MULTIPLIER) / ai.define(AiDefine.PLOT_INFLUENCE_DISTANCE_DIVISOR)
        if (city.civ.canSeeResource(tile.tileResource)) factor += ai.define(AiDefine.PLOT_BUY_RESOURCE_COST)
        if (factor > 100) cost = cost * factor / 100

        cost = cost * (city.civ.gameInfo.speed.goldCostModifier * 100).roundToInt() / 100

        var percent = 0
        city.forEachMatchingUnique(UniqueType.TileCostPercentage) { unique ->
            if (unique.params[1] != "in all cities" && city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        }
        cost = cost * (100 + percent) / 100

        val divisor = ai.define(AiDefine.PLOT_COST_APPEARANCE_DIVISOR)
        return cost / divisor * divisor
    }

    /** CvCity::GetCheapestPlotInfluence: what the city's cheapest unowned tile cost on the influence path when it last acquired a tile */
    @Readonly
    fun effectiveCheapestPlotInfluence(): Int = if (cheapestPlotInfluence >= 0) cheapestPlotInfluence else calculateCheapestPlotInfluence()

    /**
     *  CvCity::DoUpdateCheapestPlotInfluence: the lowest positive influence distance from the city to an unowned tile within the expand range
     *  (MAXIMUM_ACQUIRE_PLOT_DISTANCE 5); Int.MAX_VALUE if there is none, as the original's INT_MAX.
     */
    @Readonly
    private fun calculateCheapestPlotInfluence(): Int {
        val center = city.getCenterTile()
        val range = city.getExpandRange()
        val unowned = center.getTilesInDistance(range).filter { it.getOwner() == null }.toList()
        var lowest = Int.MAX_VALUE
        for (distance in PlotInfluence.distances(center, unowned, range).values)
            if (distance in 1..<lowest) lowest = distance
        return lowest
    }

    @Readonly
    fun getChoosableTiles() = city.getCenterTile().getTilesInDistance(city.getExpandRange())
        .filter { it.getOwner() == null }

    @Readonly
    fun chooseNewTileToOwn(): Tile? {
        // Technically, in the original a random tile with the lowest score was selected
        // However, doing this requires either caching it, which is way more work,
        // or selecting all possible tiles and only choosing one when the border expands.
        // But since the order in which tiles are selected in distance is kinda random anyways,
        // this is fine.
        return getChoosableTiles().minByOrNull {
            rankTileForExpansion(it)
        }
    }

    // Ranks a tile for the expansion algorithm of cities
    @Readonly
    fun rankTileForExpansion(tile: Tile): Int {
        // https://github.com/Gedemon/Civ5-DLL/blob/aa29e80751f541ae04858b6d2a2c7dcca454201e/CvGameCoreDLL_Expansion1/CvCity.cpp#L10301
        // Apparently this is not the full calculation. The exact tiles are also
        // dependent on which tiles are between the chosen tile and the city center
        // Exact details are not implemented, but can be found in CvAStar.cpp:2119,
        // function `InfluenceCost()`.
        // Implementing these will require an additional variable for each terrainType
        val distance = tile.aerialDistanceTo(city.getCenterTile())

        // Higher score means tile is less likely to be picked
        var score = distance * 100

        // Resources are good: less points
        val resource = tile.tileResource
        if (city.civ.canSeeResource(resource)) {
            if (resource.resourceType != ResourceType.Bonus) score -= 105
            else if (distance <= city.getWorkRange()) score -= 104
        } else {
            // Water tiles without resources aren't great unless they're Atolls
            if (tile.isWater) score += 3
            // Can't work it anyways
            if (distance > city.getWorkRange()) score += 100
        }

        if (tile.naturalWonder != null) score -= 105

        // Straight up take the sum of all yields
        score -= tile.stats.getTileStats(city, city.civ).sum().toInt()

        // Check if we get access to better tiles from this tile
        var adjacentNaturalWonder = false
        var isContested = false

        for (adjacentTile in tile.neighbors.filter { city.civ.hasExplored(it) && it.getOwner() != city.civ  }) {
            // the neighbor tile is owned by another civ
            if (adjacentTile.getOwner() != null) {
                isContested = true
                continue
            }
            // the neighbor tile is not owned by anyone
            val adjacentDistance = city.getCenterTile().aerialDistanceTo(adjacentTile)
            val adjacentResource = adjacentTile.tileResource
            if (city.civ.canSeeResource(adjacentResource) &&
                (adjacentDistance <= city.getWorkRange() || adjacentResource.resourceType != ResourceType.Bonus)
            ) score -= 1
            if (adjacentTile.naturalWonder != null) {
                if (adjacentDistance <= city.getWorkRange()) adjacentNaturalWonder = true
                score -= 1
            }
        }
        if (adjacentNaturalWonder) score -= 1
        if (isContested) score -= 1

        // Tiles not adjacent to owned land are very hard to acquire
        if (tile.neighbors.none { it.getCity() != null && it.getCity()!!.id == city.id })
            score += 1000

        return score
    }

    //region state-changing functions
    fun reset() {
        cultureLevel = 0
        cheapestPlotInfluence = 0 // CvCity::reset: stays 0 until the city acquires its first tile
        for (tile in city.getTiles())
            relinquishOwnership(tile)

        // The only way to create a city inside an owned tile is if it's in your territory
        // In this case, if you don't assign control of the central tile to the city,
        // It becomes an invisible city and weird shit starts happening
        takeOwnership(city.getCenterTile())

        // can't take ownership of owned tiles (by other cities)
        city.getCenterTile().forEachTileInDistance(1, { it.getCity() == null }) { tile ->
            takeOwnership(tile)
        }
    }

    /** CvCity::DoAcquirePlot: the tile becomes the city's, and (Civ V rules) the cheapest influence distance of its unowned tiles is worked out anew */
    fun acquireTile(tile: Tile) {
        takeOwnership(tile)
        if (city.getRuleset().modOptions.constants.plotAcquisitionLikeCivV)
            cheapestPlotInfluence = calculateCheapestPlotInfluence()
    }

    private fun addNewTileWithCulture(): HexCoord? {
        val chosenTile = chooseNewTileToOwn()
        if (chosenTile != null) {
            cultureStored -= getCultureToNextTile()
            cultureLevel = (if (cultureLevel >= 0) cultureLevel else tilesClaimed()) + 1
            acquireTile(chosenTile)
            return chosenTile.position
        }
        return null
    }

    /**
     * Removes one tile from this city's owned tiles, unconditionally, and updates dependent
     * things like worked tiles, locked tiles, and stats.
     * @param tile The tile to relinquish
     */
    fun relinquishOwnership(tile: Tile) {
        city.tiles = city.tiles.withoutItem(tile.position)
        for (city in city.civ.cities) {
            if (city.isWorked(tile)) {
                city.stopWorkingTile(tile)
                city.population.autoAssignPopulation()
            }
        }

        tile.improvementFunctions.removeCreatesOneImprovementMarker()

        tile.setOwningCity(null)
        if (tile.tileImprovement?.hasUnique(UniqueType.SiphonsLuxuriesFromCityState) == true)
            tile.updateFeitoriaBuilderResources(tile.improvementBuilder)

        city.civ.cache.updateOurTiles()
        city.cityStats.update()

        tile.history.recordRelinquishOwnership(tile)
    }

    /**
     * Takes one tile into possession of this city, either unowned or owned by any other city.
     *
     * Also manages consequences like auto population reassign, stats, and displacing units
     * that are no longer allowed on that tile.
     *
     * @param tile The tile to take over
     */
    fun takeOwnership(tile: Tile) {
        check(!tile.isCityCenter()) { "Trying to found a city in a tile that already has one" }
        if (tile.getCity() != null)
            tile.getCity()!!.expansion.relinquishOwnership(tile)

        if (tile.isBarbarianEncampment())
            tile.removeImprovement()

        city.tiles = city.tiles.withItem(tile.position)
        tile.setOwningCity(city)
        if (tile.tileImprovement?.hasUnique(UniqueType.SiphonsLuxuriesFromCityState) == true)
            tile.updateFeitoriaBuilderResources(tile.improvementBuilder)
        city.population.autoAssignPopulation()
        city.civ.cache.updateOurTiles()
        city.cityStats.update()

        for (unit in tile.getUnits().toList()) // toListed because we're modifying
            if (!unit.civ.diplomacyFunctions.canPassThroughTiles(city.civ))
                unit.movement.teleportToClosestMoveableTile()
            else if (unit.civ == city.civ && unit.isSleeping()) {
                // If the unit is sleeping and is a worker, it might want to build on this tile
                // So lets try to wake it up for the player to notice it
                if (unit.cache.hasUniqueToBuildImprovements || unit.cache.hasUniqueToCreateWaterImprovements) {
                    unit.due = true
                    unit.action = null
                }
            }

        tile.history.recordTakeOwnership(tile)
    }

    fun nextTurn(culture: Float) {
        cultureStored += culture.toInt()
        if (cultureStored >= getCultureToNextTile()) {
            val location = addNewTileWithCulture()
            if (location != null) {
                val locations = LocationAction(location, city.location)
                city.civ.addNotification("[${city.name}] has expanded its borders!", locations,
                    NotificationCategory.Cities, NotificationIcon.Culture)
            }
        }
    }

    fun setTransients() {
        if (cultureLevel < 0) cultureLevel = tilesClaimed() // saved game from before the counter existed
        val tiles = city.getTiles()
        for (tile in tiles)
            tile.setOwningCity(city)
    }
    //endregion
}
