package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/** Result of CvPlayer::GetBestSettleAreas: [count] areas (0..2) scored at least the minimum; areas are continent ids, -1 = none */
data class BestSettleAreas(val count: Int, val first: Int, val second: Int)

/**
 *  CvCitySiteEvaluator / CvSiteEvaluatorForSettler (CvSiteEvaluationClasses.cpp): the value of founding a city on a plot for one player.
 *  One instance is one evaluation pass (CvCitySiteEvaluator::ComputeFlavorMultipliers, then PlotFoundValue for many plots): it caches
 *  the per-tile parts that do not depend on the evaluated plot, so a pass over the whole map does not recompute them.
 *  Build a new instance whenever the player, its flavors or the map may have changed.
 *
 *  Unciv equivalents of the original's inputs: an area is a continent id ([Tile.getContinent]); plot yields are the terrain stats
 *  (CvPlot::calculateNatureYield, see [natureYield]); "coastal" (CvPlot::isCoastalLand(MIN_WATER_SIZE_FOR_OCEAN)) is
 *  [Tile.isAdjacentToCoast] (Unciv's lakes are their own terrain); a river plot is [Tile.isAdjacentToRiver];
 *  NUM_CITY_RINGS is the ruleset's city work range.
 */
class CitySiteEvaluator(private val civ: Civilization) {
    companion object {
        // Indexes of the flavor multipliers: the original's YieldTypes order, then SITE_EVALUATION_HAPPINESS/RESOURCES/STRATEGIC
        const val FOOD = 0
        const val PRODUCTION = 1
        const val GOLD = 2
        const val SCIENCE = 3
        const val FAITH = 4
        const val HAPPINESS = 5
        const val RESOURCES = 6
        const val STRATEGIC = 7
        private const val FACTORS = 8

        /** Happiness of a luxury resource (Resources.Happiness of every BNW luxury; Unciv's happiness per unique luxury) */
        private const val LUXURY_HAPPINESS = 4

        /** The plots counted around a site (the original loops to 7, counts yields to NUM_CITY_RINGS and unowned plots to 5) */
        private const val SEARCH_RANGE = 7
        private const val COUNT_RANGE = 5

        /** Fertility boosts of the civilization-specific code in CvCitySiteEvaluator::Init */
        private const val BRAZIL_MULTIPLIER = 1000
        private const val SPAIN_MULTIPLIER = 55000
        private const val MOROCCO_MULTIPLIER = 1000
        private const val NETHERLANDS_MULTIPLIER = 2000
        private const val INCA_MULTIPLIER = 500

        /** The original's FEATURE_MARSH and FEATURE_FLOOD_PLAINS (the Netherlands' polder sites) */
        private const val MARSH = "Marsh"
        private const val FLOOD_PLAINS = "Flood plains"

        /**
         *  CvCitySiteEvaluator::ComputeFlavorMultipliers: food = personality GROWTH + EXPANSION, gold = GOLD + TILE_IMPROVEMENT,
         *  production = PRODUCTION + WONDER, science = 2 x SCIENCE, happiness = 2 x HAPPINESS, faith = 2 x RELIGION, each plus the same
         *  flavors of the next city specialization the player wants (CvCitySpecializationAI::GetNextSpecializationDesired), at least 0;
         *  tradeable resources and strategic value 10.
         */
        fun computeFlavorMultipliers(civ: Civilization): IntArray {
            val multipliers = IntArray(FACTORS)
            val specialization = civ.ai.citySpecialization.nextSpecializationDesired?.let { name ->
                civ.gameInfo.ruleset.ai.citySpecializations.firstOrNull { it.name == name }
            }?.flavorVector
            fun flavor(flavor: Flavor) = civ.ai.personality(flavor) + (specialization?.get(flavor.ordinal) ?: 0)
            multipliers[FOOD] = flavor(Flavor.Growth) + flavor(Flavor.Expansion)
            multipliers[GOLD] = flavor(Flavor.Gold) + flavor(Flavor.TileImprovement)
            multipliers[PRODUCTION] = flavor(Flavor.Production) + flavor(Flavor.Wonder)
            multipliers[SCIENCE] = flavor(Flavor.Science) * 2
            multipliers[HAPPINESS] = flavor(Flavor.Happiness) * 2
            multipliers[FAITH] = flavor(Flavor.Religion) * 2
            for (i in multipliers.indices) if (multipliers[i] < 0) multipliers[i] = 0
            multipliers[RESOURCES] = 10
            multipliers[STRATEGIC] = 10
            return multipliers
        }

        /** CvCivilizationInfo::isCoastalCiv (Civilization_Start_Along_Ocean); a ruleset without that data: a "Coast" start bias */
        fun isCoastalCiv(civ: Civilization): Boolean {
            val coastal = civ.gameInfo.ruleset.ai.coastalCivilizations
            return if (coastal.isNotEmpty()) civ.civName in coastal
            else "Coast" in civ.nation.getStartBias(civ.gameInfo.ruleset)
        }

        /** CvPlayerTraits::WillGetUniqueLuxury (Indonesia): a city founded on [area] would get one of the civilization's unique luxuries */
        fun willGetUniqueLuxury(civ: Civilization, area: Int): Boolean {
            val unique = civ.getMatchingUniques(UniqueType.UniqueLuxuriesInNewAreas, civ.state).firstOrNull() ?: return false
            if (civ.uniqueLuxuryAreas.size >= unique.params[1].toInt()) return false
            if (civ.citiesCreated == 0) return false
            val originalCapitalArea = if (civ.originalLuxuryArea != -1) civ.originalLuxuryArea
                else civ.cities.firstOrNull { it.isOriginalCapital }?.getCenterTile()?.getContinent() ?: -1
            if (originalCapitalArea == area) return false
            if (area in civ.uniqueLuxuryAreas) return false
            val uniqueResources = civ.gameInfo.ruleset.tileResources.values.count { resource ->
                resource.getMatchingUniques(UniqueType.UniqueLuxuryForCivilization, GameContext.IgnoreConditionals)
                    .any { it.params[0] == civ.civName }
            }
            return uniqueResources > civ.uniqueLuxuryAreas.size
        }
    }

    private val gameInfo = civ.gameInfo
    private val ruleset = gameInfo.ruleset
    private val rules = ruleset.ai
    private val tileMap = gameInfo.tileMap
    private val modConstants = ruleset.modOptions.constants
    private val workRange = modConstants.cityWorkRange

    val flavorMultipliers = computeFlavorMultipliers(civ)
    private val ringModifier = intArrayOf(1, rules.define(AiDefine.CITY_RING_1_MULTIPLIER), rules.define(AiDefine.CITY_RING_2_MULTIPLIER),
        rules.define(AiDefine.CITY_RING_3_MULTIPLIER), rules.define(AiDefine.CITY_RING_4_MULTIPLIER), rules.define(AiDefine.CITY_RING_5_MULTIPLIER))
    private val foodMultiplier = rules.define(AiDefine.SETTLER_FOOD_MULTIPLIER)
    private val happinessMultiplier = rules.define(AiDefine.SETTLER_HAPPINESS_MULTIPLIER)
    private val resourceMultiplier = rules.define(AiDefine.SETTLER_RESOURCE_MULTIPLIER)
    private val strategicMultiplier = rules.define(AiDefine.SETTLER_STRATEGIC_MULTIPLIER)
    private val chokepointValue = rules.define(AiDefine.CHOKEPOINT_STRATEGIC_VALUE)
    private val hillValue = rules.define(AiDefine.HILL_STRATEGIC_VALUE)
    private val buildOnResourcePercent = rules.define(AiDefine.BUILD_ON_RESOURCE_PERCENT)
    private val buildOnRiverPercent = rules.define(AiDefine.BUILD_ON_RIVER_PERCENT)
    private val buildOnCoastPercent = rules.define(AiDefine.SETTLER_BUILD_ON_COAST_PERCENT)
    /** The settler multipliers per yield, in [FOOD]..[FAITH] order */
    private val yieldMultipliers = intArrayOf(foodMultiplier, rules.define(AiDefine.SETTLER_PRODUCTION_MULTIPLIER),
        rules.define(AiDefine.SETTLER_GOLD_MULTIPLIER), rules.define(AiDefine.SETTLER_SCIENCE_MULTIPLIER), rules.define(AiDefine.SETTLER_FAITH_MULTIPLIER))

    private val isHuman = civ.isHuman()
    private val capitalArea = civ.getCapital()?.getCenterTile()?.getContinent() ?: -1
    private val navalFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Naval)
    private val growthFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Growth)
    private val expansionFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Expansion)
    private val boldness = civ.ai.diplomacyValue(DiplomacyPersonality.Boldness)
    private val isCoastalCiv = isCoastalCiv(civ)
    private val ownedResources = civ.getCivResourcesByName()

    // Civilization-specific site preferences (CvCitySiteEvaluator::PlotFoundValue)
    /** CvPlayerTraits::IsFaithFromUnimprovedForest (Celts): faith from unimproved forests next to the city */
    private val isCeltic = civ.getMatchingUniques(UniqueType.StatsPerCity, GameContext.IgnoreConditionals).any { unique ->
        unique.stats.faith > 0 && unique.modifiers.any {
            it.type == UniqueType.ConditionalNeighborTiles && "Forest" in it.params[2] && "unimproved" in it.params[2]
        }
    }
    /** CvPlayerTraits::IsMoveFriendlyWoodsAsRoad (Iroquois) */
    private val isIroquois = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads, GameContext.IgnoreConditionals)
    /** CvPlayerTraits::GetNaturalWonderYieldModifier() > 0 (Spain) */
    private val isSpain = civ.getMatchingUniques(UniqueType.AllStatsPercentFromObject, GameContext.IgnoreConditionals)
        .any { it.params[1] == "Natural Wonder" && it.params[0].toInt() > 0 }
    private val isBrazil = isUniqueImprovementOwner("Brazilwood Camp")
    private val isMorocco = isUniqueImprovementOwner("Kasbah")
    private val isNetherlands = isUniqueImprovementOwner("Polder")
    private val isInca = isUniqueImprovementOwner("Terrace farm")

    /** The original looks these up by their XML type (IMPROVEMENT_BRAZILWOOD_CAMP, ...) and the civilization they require */
    private fun isUniqueImprovementOwner(improvement: String) = ruleset.tileImprovements[improvement]?.uniqueTo == civ.civName

    /** Per tile: the ring-independent yield value (Σ Compute<Yield>Value × SETTLER_<YIELD>_MULTIPLIER), [UNSET] until computed */
    private val yieldValueCache = IntArray(tileMap.tileList.size) { UNSET }
    /** Per tile: ComputeHappinessValue × SETTLER_HAPPINESS_MULTIPLIER + ComputeTradeableResourceValue × SETTLER_RESOURCE_MULTIPLIER */
    private val unownedValueCache = IntArray(tileMap.tileList.size) { UNSET }
    /** Per tile: true if a city stands within the minimum city distance (CvCitySiteEvaluator::CanFound's MIN_CITY_RANGE test) */
    private val blockedByCity = BooleanArray(tileMap.tileList.size).also { blocked ->
        for (otherCiv in gameInfo.civilizations) for (city in otherCiv.cities) {
            val center = city.getCenterTile()
            val sameLandmass = center.getContinent()
            val range = max(modConstants.minimalCityDistance, modConstants.minimalCityDistanceOnDifferentContinents)
            center.forEachTileInDistance(range) { tile ->
                val distance = tile.aerialDistanceTo(center)
                if (distance <= modConstants.minimalCityDistanceOnDifferentContinents
                    || distance <= modConstants.minimalCityDistance && tile.getContinent() == sameLandmass)
                    blocked[tile.zeroBasedIndex] = true
            }
        }
    }

    // State of the plot being evaluated, read by [visit]
    private var plotValue = 0
    private var distance = 0
    private var closestCityOfMine = 0
    private var closestEnemyCity = 0
    private var celticForestCount = 0
    private var iroquoisForestCount = 0
    private var brazilJungleCount = 0
    private var naturalWonderCount = 0
    private var desertCount = 0
    private var wetlandsCount = 0
    private val visitor: (Tile) -> Unit = { visit(it) }

    /**
     *  CvCitySiteEvaluator::CanFound (bTestVisible = false): land, passable, not owned by another player, and no city within the
     *  minimum city distance (one less across water). The ruleset's distances, as Unciv's [Tile.canBeSettled].
     */
    fun canFound(tile: Tile): Boolean {
        if (tile.isWater || tile.isImpassible()) return false
        val owner = tile.getOwner()
        if (owner != null && owner != civ) return false
        return !blockedByCity[tile.zeroBasedIndex]
    }

    /**
     *  CvSiteEvaluatorForSettler::PlotFoundValue: [plotFoundValue] doubled where a city would get one of the civilization's unique
     *  luxuries (CvPlayerTraits::WillGetUniqueLuxury, Indonesia). 0 where no city can be founded.
     */
    fun settlerPlotFoundValue(tile: Tile): Int {
        if (!canFound(tile)) return 0
        val value = plotFoundValue(tile)
        return if (willGetUniqueLuxury(civ, tile.getContinent())) value * 2 else value
    }

    /**
     *  CvCitySiteEvaluator::PlotFoundValue: the value of a city on [tile]. Every plot within 5 that is unowned or ours counts, by ring
     *  (CITY_RING_n_MULTIPLIER): flavor-weighted yields for rings 1..NUM_CITY_RINGS, and for unowned plots happiness, tradeable
     *  resources and strategic value; a plot worth nothing costs ring × SETTLER_FOOD_MULTIPLIER × 2, a natural wonder in the rings adds
     *  2 × its value + 500, a plot we own counts a quarter. Then the civilization-specific bonuses; then, for the city plot itself:
     *  resource BUILD_ON_RESOURCE_PERCENT, river BUILD_ON_RIVER_PERCENT, coast SETTLER_BUILD_ON_COAST_PERCENT (twice with NAVAL flavor
     *  over 7, and +25 then doubled for coastal civilizations). Last the spacing: a human halves sites 3 from its cities; an AI prefers
     *  its "sweet spot" (5, ±1 by GROWTH/EXPANSION flavor over 7 or under 4, within 4..6) ×1.75, halves closer sites, takes 2/3 beyond 7;
     *  boldness under 4 avoids foreign major cities (/4 within 4, /2 at 5), over 7 seeks them (×1.5 within 5 if one of ours is within 7),
     *  else 2/3 within 4; sites off the capital's landmass within 7 of one of our cities ×1.5.
     *
     *  Not ported: the original never resets its faith value for plots outside the city rings, so a ring-0/4/5 plot repeats the faith
     *  value of the previous plot in its loop order (an evaluation-order artifact of its offset coordinates); here such plots count no faith.
     */
    fun plotFoundValue(tile: Tile): Int {
        if (!canFound(tile)) return 0
        plotValue = 0
        closestCityOfMine = 999
        closestEnemyCity = 999
        celticForestCount = 0
        iroquoisForestCount = 0
        brazilJungleCount = 0
        naturalWonderCount = 0
        desertCount = 0
        wetlandsCount = 0
        for (d in 0..SEARCH_RANGE) {
            distance = d
            tileMap.forEachTileAtDistance(tile.position, d, visitor)
        }
        var value = plotValue

        if (isCeltic) {
            if (celticForestCount >= 3) value += 2 * 1000 * flavorMultipliers[FAITH]
            else if (celticForestCount >= 1) value += 1000 * flavorMultipliers[FAITH]
        } else if (isIroquois) value += iroquoisForestCount * 10
        else if (isSpain) value += naturalWonderCount * SPAIN_MULTIPLIER
        if (isBrazil) value += brazilJungleCount * BRAZIL_MULTIPLIER
        if (isMorocco) value += desertCount * MOROCCO_MULTIPLIER
        if (isNetherlands) value += wetlandsCount * NETHERLANDS_MULTIPLIER
        if (value < 0) value = 0

        // The city plot itself as an overall multiplier
        if (tile.resource != null && civ.canSeeResource(tile.tileResource)) value += value * buildOnResourcePercent / 100
        if (tile.isAdjacentToRiver()) value += value * buildOnRiverPercent / 100
        if (tile.isAdjacentToCoast()) {
            value += value * buildOnCoastPercent / 100
            if (navalFlavor > 7) value += value * buildOnCoastPercent / 100
            if (isCoastalCiv) {
                if (value > 0) value += 25
                value *= 2
            }
        }

        if (isHuman) {
            if (closestCityOfMine == 3) value /= 2
        } else {
            var sweetSpot = 5
            if (growthFlavor > 7) sweetSpot++
            if (expansionFlavor > 7) sweetSpot--
            if (growthFlavor < 4) sweetSpot--
            if (expansionFlavor < 4) sweetSpot++
            sweetSpot = sweetSpot.coerceIn(4, 6)
            when {
                closestCityOfMine == sweetSpot -> value = value * 175 / 100
                closestCityOfMine < sweetSpot -> value /= 2
                closestCityOfMine > 7 -> value = value * 2 / 3
            }

            if (boldness < 4) {
                if (closestEnemyCity <= 4) value /= 4
                else if (closestEnemyCity == 5) value /= 2
            } else if (boldness > 7) {
                if (closestEnemyCity <= 5 && closestCityOfMine < 8) value = value * 3 / 2
            } else if (closestEnemyCity < 5) value = value * 2 / 3

            // Offshore: pull cities in tighter
            if (capitalArea != tile.getContinent() && closestCityOfMine < 7) value = value * 3 / 2
        }
        return max(0, value)
    }

    private fun visit(loopTile: Tile) {
        val d = distance
        val owner = loopTile.getOwner()
        if (owner != null && owner != civ) {
            if (loopTile.isCityCenter() && owner.isMajorCiv && closestEnemyCity > d) closestEnemyCity = d
            return
        }
        if (loopTile.isCityCenter() && closestCityOfMine > d) closestCityOfMine = d
        if (d > COUNT_RANGE) return

        val ring = ringModifier[d]
        var value = if (d in 1..workRange) ring * yieldValue(loopTile) else 0
        var strategic = 0
        if (owner == null) {
            value += ring * unownedValue(loopTile)
            if (d > 0) strategic = strategicValue(loopTile, d) * strategicMultiplier
        }
        if (value == 0) value -= ring * foodMultiplier * 2
        value += strategic
        if (loopTile.isNaturalWonder() && d in 1..workRange) value += value * 2 + 500
        if (value > 0 && owner == civ) value /= 4
        plotValue += value

        val features = loopTile.terrainFeatures
        if (Constants.forest in features) {
            iroquoisForestCount++
            if (d == 1 && loopTile.improvement == null) celticForestCount++
        } else if (Constants.jungle in features) {
            if (d <= workRange) brazilJungleCount++
        } else if (MARSH in features || FLOOD_PLAINS in features) {
            if (d <= workRange) wetlandsCount++
        }
        if (loopTile.isNaturalWonder() && d <= 1) naturalWonderCount++
        if (loopTile.baseTerrain == Constants.desert && d <= workRange && loopTile.resource == null) desertCount++
        if (isInca && loopTile.isHill() && d <= workRange) {
            var adjacentMountains = 0
            for (neighbor in loopTile.neighbors) if (neighbor.baseTerrain == Constants.mountain) adjacentMountains++
            if (adjacentMountains in 1..5) plotValue += INCA_MULTIPLIER + adjacentMountains * INCA_MULTIPLIER
        }
    }

    /** Σ over food..faith of Compute<Yield>Value (yield × flavor multiplier) × SETTLER_<YIELD>_MULTIPLIER, cached per tile */
    private fun yieldValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = yieldValueCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var sum = 0
        for (factor in FOOD..FAITH) {
            var yield = natureYield(tile, factor, resource)
            if (resource != null) yield += resourceYield(resource, factor)
            sum += yield * flavorMultipliers[factor] * yieldMultipliers[factor]
        }
        yieldValueCache[index] = sum
        return sum
    }

    /** ComputeHappinessValue × SETTLER_HAPPINESS_MULTIPLIER + ComputeTradeableResourceValue × SETTLER_RESOURCE_MULTIPLIER, for an unowned plot */
    private fun unownedValue(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        val cached = unownedValueCache[index]
        if (cached != UNSET) return cached
        val resource = visibleResource(tile)
        var happiness = 0
        var tradeable = 0
        if (resource != null) {
            val notOwned = (ownedResources[resource.name] ?: 0) <= 0
            if (resource.resourceType == ResourceType.Luxury) happiness = LUXURY_HAPPINESS
            if (notOwned) happiness *= 5
            if (resource.resourceType != ResourceType.Bonus) {
                val quantity = if (resource.resourceType == ResourceType.Strategic) tile.resourceAmount else 1
                tradeable = quantity * flavorMultipliers[RESOURCES]
                if (notOwned) tradeable *= 3
            }
        }
        val value = happiness * flavorMultipliers[HAPPINESS] * happinessMultiplier + tradeable * resourceMultiplier
        unownedValueCache[index] = value
        return value
    }

    /** CvCitySiteEvaluator::ComputeStrategicValue: chokepoint 2 away, hill next to the city, the feature's starting location weight next to it */
    private fun strategicValue(tile: Tile, plotsFromCity: Int): Int {
        var value = 0
        if (plotsFromCity == 2 && tile.isImpassible()) value += chokepointValue
        if (plotsFromCity == 1) {
            if (tile.isHill()) value += hillValue
            for (feature in tile.terrainFeatures) value += rules.featureStartingLocationWeights[feature] ?: 0
        }
        return value * flavorMultipliers[STRATEGIC]
    }

    private fun visibleResource(tile: Tile): TileResource? =
        if (tile.resource != null && civ.canSeeResource(tile.tileResource)) tile.tileResource else null

    /**
     *  CvPlot::calculateNatureYield: the terrain yield (base terrain, features - some replace it -, natural wonder) plus the resource's
     *  yield if the player can see it; 0 on impassable plots except natural wonders.
     */
    private fun natureYield(tile: Tile, factor: Int, resource: TileResource?): Int {
        if (tile.isImpassible() && !tile.isNaturalWonder()) return 0
        var yield = 0f
        for (terrain in tile.allTerrains) {
            val value = statOf(terrain, factor)
            if (terrain.overrideStats || terrain.hasUnique(UniqueType.NullifyYields)) yield = value else yield += value
            if (terrain.hasUnique(UniqueType.NullifyYields)) break
        }
        if (resource != null) yield += statOf(resource, factor)
        return yield.toInt()
    }

    /** The resource's yield plus the yield its improvement adds (CvImprovementEntry::GetImprovementResourceYield) */
    private fun resourceYield(resource: TileResource, factor: Int): Int =
        (statOf(resource, factor) + (resource.improvementStats?.let { statOf(it, factor) } ?: 0f)).toInt()

    private fun statOf(stats: Stats, factor: Int): Float = when (factor) {
        FOOD -> stats.food
        PRODUCTION -> stats.production
        GOLD -> stats.gold
        SCIENCE -> stats.science
        else -> stats.faith
    }
}

private const val UNSET = Int.MIN_VALUE

/**
 *  The settling map of each player: CvPlayerAI::AI_updateFoundValues (found value of every revealed plot, once per turn, and their sum
 *  per landmass), CvPlayer::GetBestSettleAreas, EconomicAIHelpers::IsAreaSafeForQuickColony and CvPlayer::GetBestSettlePlot.
 *  Found values are computed on first use in a turn (the original computes them at the start of the player's turn, CvPlayerAI::AI_doTurnPre)
 *  and kept for the rest of the turn, as in the original.
 */
object SettleAreas {
    /** The radius around the other civilization's cities that a no-settle promise blocks (CvDiplomacyAI::SetPlayerNoSettleRequestAccepted) */
    private const val PROMISED_NO_SETTLE_RANGE = 6

    /** A player's found values of one turn: by [Tile.zeroBasedIndex], -1 for unrevealed plots; sum per continent of the values at least
     *  AI_STRATEGY_MINIMUM_SETTLE_FERTILITY (CvArea::getTotalFoundValue) */
    class FoundValues(val turn: Int, val values: IntArray, val areaTotals: Map<Int, Int>)

    private val cache = WeakHashMap<Civilization, FoundValues>()

    /** CvMap::GetAIMapHint, set by the original's map scripts: 1 = naval map, 4 = offshore expansion map (Archipelago 1+4, Small Continents 4) */
    fun aiMapHint(gameInfo: GameInfo): Int = when (gameInfo.tileMap.mapParameters.type) {
        MapType.archipelago -> 1 + 4
        MapType.smallContinents -> 4
        else -> 0
    }

    /** This turn's found values of [civ], computed on first use */
    fun foundValues(civ: Civilization): FoundValues {
        synchronized(cache) {
            val cached = cache[civ]
            if (cached != null && cached.turn == civ.gameInfo.turns) return cached
        }
        return updateFoundValues(civ)
    }

    /** The found value of [tile] for [civ] this turn (CvPlot::getFoundValue): -1 if unrevealed, 0 if no city can be founded there */
    fun foundValue(civ: Civilization, tile: Tile): Int = foundValues(civ).values[tile.zeroBasedIndex]

    /**
     *  CvPlayerAI::AI_updateFoundValues: the settler found value ([CitySiteEvaluator.settlerPlotFoundValue]) of every plot [civ] has
     *  revealed, -1 elsewhere; per landmass, the sum of the values of at least AI_STRATEGY_MINIMUM_SETTLE_FERTILITY.
     */
    fun updateFoundValues(civ: Civilization): FoundValues = timeThis("AI.UpdateFoundValues") {
        val tiles = civ.gameInfo.tileMap.tileList
        val minimum = civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)
        val values = IntArray(tiles.size)
        val totals = HashMap<Int, Int>()
        val evaluator = CitySiteEvaluator(civ)
        for (tile in tiles) {
            if (!civ.hasExplored(tile)) {
                values[tile.zeroBasedIndex] = -1
                continue
            }
            val value = evaluator.settlerPlotFoundValue(tile)
            values[tile.zeroBasedIndex] = value
            val area = tile.getContinent()
            if (value >= minimum && tile.isLand && area != -1) totals[area] = (totals[area] ?: 0) + value
        }
        val result = FoundValues(civ.gameInfo.turns, values, totals)
        synchronized(cache) { cache[civ] = result }
        result
    }

    /**
     *  CvPlayer::GetBestSettleAreas: the two landmasses with the highest total found value of at least [minScore]; a landmass not
     *  safe for a quick colony counts a third, unless the map is an offshore expansion map. Areas are compared in id order, ties keep
     *  the first; as in the original, a best area is demoted to second only if its score exceeded [minScore].
     */
    fun bestSettleAreas(civ: Civilization, minScore: Int): BestSettleAreas = timeThis("AI.BestSettleAreas") {
        val totals = foundValues(civ).areaTotals
        val offshoreMap = aiMapHint(civ.gameInfo) and 4 != 0
        var bestScore = -1
        var secondBestScore = -1
        var bestArea = -1
        var secondBestArea = -1
        val unsafeAreas = if (offshoreMap) emptySet() else areasWithVisibleEnemies(civ)
        for (area in totals.keys.sorted()) {
            var score = totals[area]!!
            if (score < minScore) continue
            if (!offshoreMap && !isAreaSafeForQuickColony(civ, area, unsafeAreas)) score /= 3
            if (score > bestScore) {
                if (bestScore > minScore) {
                    secondBestScore = bestScore
                    secondBestArea = bestArea
                }
                bestArea = area
                bestScore = score
            } else if (score > secondBestScore) {
                secondBestArea = area
                secondBestScore = score
            }
        }
        val count = if (secondBestArea != -1) 2 else if (bestArea != -1) 1 else 0
        BestSettleAreas(count, bestArea, secondBestArea)
    }

    /** EconomicAIHelpers::IsAreaSafeForQuickColony: a landmass other than the capital's with no enemy unit [civ] can see on it */
    fun isAreaSafeForQuickColony(civ: Civilization, area: Int): Boolean = isAreaSafeForQuickColony(civ, area, areasWithVisibleEnemies(civ))

    private fun isAreaSafeForQuickColony(civ: Civilization, area: Int, areasWithVisibleEnemies: Set<Int>): Boolean {
        if (area == -1) return false
        if (civ.getCapital()?.getCenterTile()?.getContinent() == area) return false
        return area !in areasWithVisibleEnemies
    }

    /** Continents with a tile holding a unit of a player at war with [civ] that [civ] can see (CvPlot::isVisibleEnemyUnit) */
    private fun areasWithVisibleEnemies(civ: Civilization): Set<Int> {
        val areas = HashSet<Int>()
        for (tile in civ.viewableTiles) {
            if (tile.getContinent() == -1 || tile.getContinent() in areas) continue
            if (isVisibleEnemyUnit(civ, tile)) areas.add(tile.getContinent())
        }
        return areas
    }

    /** CvPlot::isVisibleEnemyUnit: a unit of a player at war with [civ] on [tile], which [civ] sees */
    fun isVisibleEnemyUnit(civ: Civilization, tile: Tile): Boolean {
        if (tile !in civ.viewableTiles) return false
        for (unit in tile.getUnits()) if (unit.civ != civ && civ.isAtWarWith(unit.civ) && unit.isVisibleTo(civ)) return true
        return false
    }

    /**
     *  CvUnit::canFound: [unit] can found a city and the plot allows one (CvCitySiteEvaluator::CanFound via [Tile.canBeSettled]); and
     *  CvPlayer::canFound's first test, the plot is not one the civilization agreed not to settle ([isPromisedNotToSettle])
     */
    fun canFound(unit: MapUnit, tile: Tile): Boolean =
        UnitActionsFromUniques.getFoundCityAction(unit, tile) != null && tile.canBeSettled(unit.civ) && !isPromisedNotToSettle(unit.civ, tile)

    /**
     *  CvPlot::IsNoSettling as CvDiplomacyAI::SetPlayerNoSettleRequestAccepted sets it: [tile] is within 6 of a city of a civilization that
     *  [civ] agreed not to settle near ([DiplomacyFlags.AgreedToNotSettleNearUs], which a war ends). Not for city-states or barbarians.
     */
    fun isPromisedNotToSettle(civ: Civilization, tile: Tile): Boolean {
        if (!civ.isMajorCiv) return false
        return civ.getKnownCivs().any { other ->
            other.isMajorCiv && !civ.isAtWarWith(other) && other.getDiplomacyManager(civ)!!.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) &&
                other.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= PROMISED_NO_SETTLE_RANGE }
        }
    }

    /**
     *  CvPlayer::GetBestSettlePlot: the best revealed plot in the world for [unit] to settle, unowned or ours, that it can found on,
     *  in [area] (-1 = any), not next to another player's territory, not within 2 of a site another settler targets ([targetedSites],
     *  CvPlayer::IsPlotTargetedForCity), and - unless [escorted] - with no visible enemy unit. Only found values over 5000 count, reduced
     *  by distance: value × (100 - min(99, SETTLER_DISTANCE_DROPOFF_MODIFIER × distance / evalDistance)) / 100, where evalDistance =
     *  SETTLER_EVALUATION_DISTANCE + turn × 5 / 100, scaled up to the map size (80×52 plots = 1), ×3/2 when escorted on a naval or offshore
     *  map, ×2/3 unescorted; a plot on another landmass ×3/2 on naval/offshore maps, else ×2/3. Null if none.
     *  [scores] (optional) receives the final score of every candidate.
     */
    fun bestSettlePlot(unit: MapUnit, escorted: Boolean, area: Int, targetedSites: Collection<Tile>,
                       scores: MutableMap<Tile, Int>? = null): Tile? = timeThis("AI.BestSettlePlot") {
        val civ = unit.civ
        val gameInfo = civ.gameInfo
        val rules = gameInfo.ruleset.ai
        val values = foundValues(civ).values
        val unitTile = unit.getTile()
        val unitArea = unitTile.getContinent()
        val mapHint = aiMapHint(gameInfo)

        var evalDistance = rules.define(AiDefine.SETTLER_EVALUATION_DISTANCE) + gameInfo.turns * 5 / 100
        val defaultEvalDistance = evalDistance
        evalDistance = max(defaultEvalDistance, evalDistance * gameInfo.tileMap.tileList.size / (80 * 52))
        if (escorted && mapHint and 5 != 0) evalDistance = evalDistance * 3 / 2
        else if (!escorted) evalDistance = evalDistance * 2 / 3
        val dropoffModifier = rules.define(AiDefine.SETTLER_DISTANCE_DROPOFF_MODIFIER)

        var bestValue = 0
        var bestTile: Tile? = null
        for (tile in gameInfo.tileMap.tileList) {
            var value = values[tile.zeroBasedIndex]
            if (value <= 5000) continue
            val owner = tile.getOwner()
            if (owner != null && owner != civ) continue
            if (area != -1 && tile.getContinent() != area) continue
            if (tile.neighbors.any { val neighborOwner = it.getOwner(); neighborOwner != null && neighborOwner != civ }) continue
            if (targetedSites.any { it.aerialDistanceTo(tile) <= 2 }) continue
            if (!escorted && isVisibleEnemyUnit(civ, tile)) continue
            if (!canFound(unit, tile)) continue
            val dropoff = min(99, dropoffModifier * tile.aerialDistanceTo(unitTile) / evalDistance).coerceAtLeast(0)
            value = value * (100 - dropoff) / 100
            if (tile.getContinent() != unitArea) {
                value = if (mapHint and 5 != 0) value * 3 / 2 else value * 2 / 3
            }
            scores?.put(tile, value)
            if (value > bestValue) {
                bestValue = value
                bestTile = tile
            }
        }
        bestTile
    }
}
