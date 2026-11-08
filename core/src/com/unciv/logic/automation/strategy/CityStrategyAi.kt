package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import java.util.WeakHashMap
import kotlin.math.floor

/**
 *  The city strategy tests (CvCityStrategyAI.cpp: CityStrategyAIHelpers::IsTestCityStrategy_*) and the helpers of CvCityStrategyAI that
 *  other ports read (IsYieldDeficient). ReweightByTurnsLeft is [WonderProductionAi.reweightByTurnsLeft], GetNumUnitsWithUnitAI [UnitAiTypes].
 *
 *  Not registered here: the trade and espionage city strategies (registered by their own port) and AICITYSTRATEGY_CAPITAL_UNDER_THREAT,
 *  which reads CvCity::getThreatValue - the CvDangerPlots danger map of the unit-level AI, which is not ported - so it is never active.
 */
object CityStrategyAi {
    private const val NEED_TILE_IMPROVERS = "AICITYSTRATEGY_NEED_TILE_IMPROVERS"
    private const val NEED_NAVAL_TILE_IMPROVEMENT = "AICITYSTRATEGY_NEED_NAVAL_TILE_IMPROVEMENT"
    private const val CAPITAL_UNDER_THREAT = "AICITYSTRATEGY_CAPITAL_UNDER_THREAT"

    //region Helpers

    /**
     *  Per civilization and turn: the counts and lists the tests of all its cities share (CvPlayer::GetNumUnitsWithUnitAI,
     *  countCitiesFeatureSurrounded, ...), and each city's deficient yield. Rebuilt on a new turn and by [clearTurnCache].
     */
    private class TurnCache(val turn: Int) {
        var landWorkers = -1
        var seaWorkers = -1
        var landSettlers = -1
        var allSettlers = -1
        var featureSurrounded = -1
        var enemyNavalTiles: List<Tile>? = null
        val deficientYields = HashMap<String, Stat?>()
    }
    private val turnCaches = WeakHashMap<Civilization, TurnCache>()

    private fun turnCache(civ: Civilization): TurnCache = synchronized(turnCaches) {
        val turn = civ.gameInfo.turns
        turnCaches[civ]?.takeIf { it.turn == turn } ?: TurnCache(turn).also { turnCaches[civ] = it }
    }

    /** Forgets the shared values of [civ] - at the start of its city strategy turn, as units and cities may have changed since */
    internal fun clearTurnCache(civ: Civilization) {
        synchronized(turnCaches) { turnCaches.remove(civ) }
    }

    private fun numLandWorkers(civ: Civilization): Int = turnCache(civ).run {
        if (landWorkers < 0) landWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false)
        landWorkers
    }
    private fun numSeaWorkers(civ: Civilization): Int = turnCache(civ).run {
        if (seaWorkers < 0) seaWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER_SEA, includeBeingTrained = true, includeWater = true)
        seaWorkers
    }
    private fun numSettlers(civ: Civilization, includeWater: Boolean): Int = turnCache(civ).run {
        if (includeWater) {
            if (allSettlers < 0) allSettlers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true, includeWater = true)
            allSettlers
        } else {
            if (landSettlers < 0) landSettlers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true, includeWater = false)
            landSettlers
        }
    }

    /** CvCityStrategyAI::GetYieldAverage for every yield at once: the yields of the tiles the city works (its center included) and their count */
    private fun workedTileYields(city: City): Pair<Stats, Int> {
        val sum = Stats()
        var tiles = 0
        for (tile in city.getWorkedTiles() + sequenceOf(city.getCenterTile())) {
            tiles++
            val stats = tile.stats.getTileStats(city, city.civ)
            for ((stat, value) in stats) sum[stat] += value.toInt().toFloat()
        }
        return Pair(sum, tiles)
    }

    private fun isDeficient(rules: AiRuleset, stat: Stat, yields: Pair<Stats, Int>): Boolean {
        val desired = deficientYieldValue(rules, stat) ?: return false
        val average = if (yields.second == 0) 0.0 else yields.first[stat].toDouble() / yields.second
        return roundTimes100(average) < roundTimes100(desired.toDouble())
    }

    /** CvCityStrategyAI::GetDeficientYieldValue: the desired average yield, null for the yields the original never finds deficient */
    private fun deficientYieldValue(rules: AiRuleset, stat: Stat): Float? = when (stat) {
        Stat.Food -> rules.defineFloat(AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_FOOD)
        Stat.Production -> rules.defineFloat(AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_PRODUCTION)
        Stat.Science -> rules.defineFloat(AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_SCIENCE)
        Stat.Gold -> rules.defineFloat(AiDefine.AI_CITYSTRATEGY_YIELD_DEFICIENT_GOLD)
        else -> null
    }

    private fun roundTimes100(value: Double): Int = floor(value * 100 + 0.5).toInt()

    /** CvCityStrategyAI::IsYieldDeficient: the city's average worked-tile [stat] is below the desired value (compared ×100, rounded) */
    fun isYieldDeficient(city: City, stat: Stat): Boolean = isDeficient(city.civ.gameInfo.ruleset.ai, stat, workedTileYields(city))

    /** CvCityStrategyAI::GetDeficientYield: food first, then production, then the other yields in order; null if none */
    fun deficientYield(city: City): Stat? {
        val rules = city.civ.gameInfo.ruleset.ai
        val yields = workedTileYields(city)
        if (isDeficient(rules, Stat.Food, yields)) return Stat.Food
        if (isDeficient(rules, Stat.Production, yields)) return Stat.Production
        return Stat.entries.firstOrNull { isDeficient(rules, it, yields) }
    }

    /** [deficientYield], once per city and turn for its two strategies */
    private fun deficientYieldThisTurn(city: City): Stat? {
        val cache = turnCache(city.civ)
        synchronized(cache) {
            if (city.id in cache.deficientYields) return cache.deficientYields[city.id]
        }
        val result = deficientYield(city)
        synchronized(cache) { cache.deficientYields[city.id] = result }
        return result
    }

    /** The stats of the city's buildings (the "Buildings" branch of its base stats) */
    private fun buildingYield(city: City, stat: Stat): Float = city.cityStats.baseStatTree.children["Buildings"]?.totalStats?.get(stat) ?: 0f

    /** CvCity::getYieldRateModifier: the percentage bonus of the city's buildings */
    fun buildingYieldModifier(city: City, stat: Stat): Float = city.cityStats.statPercentBonusTree.children["Buildings"]?.totalStats?.get(stat) ?: 0f

    /** CvCity::GetBaseYieldRateFromBuildings */
    fun baseYieldFromBuildings(city: City, stat: Stat): Int = buildingYield(city, stat).toInt()

    /** CvCity::GetYieldPerPopTimes100: what the city's buildings give per citizen, ×100 */
    fun yieldPerPopTimes100(city: City, stat: Stat): Int {
        var total = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.StatsPerPopulation, city.state)) {
                val perAmount = unique.params[1].toInt()
                if (perAmount > 0) total += (unique.stats[stat] * 100 / perAmount).toInt()
            }
        return total
    }

    private val landUnitFilterCache = WeakHashMap<AiRuleset, HashMap<String, Boolean>>()

    /** CvCity::getDomainFreeExperience(DOMAIN_LAND): the experience the city's buildings give new land military units */
    fun landFreeExperience(city: City): Int {
        val ruleset = city.civ.gameInfo.ruleset
        val cache = synchronized(landUnitFilterCache) { landUnitFilterCache.getOrPut(ruleset.ai) { HashMap() } }
        var experience = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.UnitStartingExperience, city.state)) {
                val filter = unique.params[0]
                val matchesLand = synchronized(cache) {
                    cache.getOrPut(filter) { ruleset.units.values.any { it.isLandUnit && it.isMilitary && it.matchesFilter(filter) } }
                }
                if (matchesLand && city.matchesFilter(unique.params[2])) experience += unique.params[1].toInt()
            }
        return experience
    }

    private val removableFeatures = WeakHashMap<AiRuleset, Set<String>>()

    /** CvCity::IsFeatureSurrounded (DoUpdateFeatureSurrounded): at least half of the 25 places of the 5×5 square around the city - the
     *  original counts the square, though only the 19 tiles within 2 can hold a feature - have a removable feature */
    private fun isFeatureSurrounded(city: City, removable: Set<String>): Boolean {
        var featured = 0
        city.getCenterTile().forEachTileInDistance(2) { tile ->
            if (tile.terrainFeatures.any { it in removable }) featured++
        }
        return featured >= 25 / 2
    }

    /** CvPlayer::countCitiesFeatureSurrounded, once per turn */
    private fun countCitiesFeatureSurrounded(civ: Civilization): Int = turnCache(civ).run {
        if (featureSurrounded < 0) {
            val ruleset = civ.gameInfo.ruleset
            val removable = synchronized(removableFeatures) {
                removableFeatures.getOrPut(ruleset.ai) {
                    ruleset.terrains.keys.filterTo(HashSet()) { "${Constants.remove}$it" in ruleset.tileImprovements }
                }
            }
            featureSurrounded = civ.cities.count { isFeatureSurrounded(it, removable) }
        }
        featureSurrounded
    }

    /** Tiles within 2 of the city owned by its owner */
    private fun countOwnedTilesWithin2(city: City, predicate: (Tile) -> Boolean): Int {
        var count = 0
        city.getCenterTile().forEachTileInDistance(2) { if (it.getOwner() == city.civ && predicate(it)) count++ }
        return count
    }

    /** GC.getGame().getHandicapInfo().GetID(): the index of the game's difficulty (Settler 0 ... Deity 7) */
    fun gameHandicapIndex(civ: Civilization): Int = civ.gameInfo.ruleset.difficulties.keys.indexOf(civ.gameInfo.getDifficulty().name)

    /** GameSpeedInfo::getTrainPercent */
    private fun trainPercent(civ: Civilization): Int = (civ.gameInfo.speed.productionCostModifier * 100).toInt()
    //endregion

    //region Tests

    fun isTinyCity(city: City): Boolean =
        city.population.population < city.civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_SMALL_CITY_POP_THRESHOLD)

    fun isSmallCity(city: City): Boolean {
        val rules = city.civ.gameInfo.ruleset.ai
        val population = city.population.population
        return population >= rules.define(AiDefine.AI_CITYSTRATEGY_SMALL_CITY_POP_THRESHOLD)
            && population < rules.define(AiDefine.AI_CITYSTRATEGY_MEDIUM_CITY_POP_THRESHOLD)
    }

    /** Never the capital, so that one city keeps its high SPACESHIP and similar flavors */
    fun isMediumCity(city: City): Boolean {
        if (city.isCapital()) return false
        val rules = city.civ.gameInfo.ruleset.ai
        val population = city.population.population
        return population >= rules.define(AiDefine.AI_CITYSTRATEGY_MEDIUM_CITY_POP_THRESHOLD)
            && population < rules.define(AiDefine.AI_CITYSTRATEGY_LARGE_CITY_POP_THRESHOLD)
    }

    fun isLargeCity(city: City): Boolean =
        city.population.population >= city.civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_LARGE_CITY_POP_THRESHOLD)

    fun needTileImprovers(city: City, weightThreshold: Int): Boolean {
        val civ = city.civ
        val numCities = civ.cities.size
        val numWorkers = numLandWorkers(civ)
        if (civ.isCityState) {
            if (numWorkers >= numCities) return false
        } else {
            if (numWorkers >= maxOf(1, numCities * 3 / 4)) return false
            if (WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return false
        }
        if (numCities <= 1 && civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")) return false
        val moddedNumWorkers = numWorkers * weightThreshold / 100
        val moddedNumCities = numCities + countCitiesFeatureSurrounded(civ)
        if (moddedNumWorkers <= moddedNumCities || moddedNumWorkers == 0) {
            val desperateTurn = civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_NEED_TILE_IMPROVERS_DESPERATE_TURN) * trainPercent(civ) / 100
            if (civ.gameInfo.turns > desperateTurn) return true
        }
        return false
    }

    fun wantTileImprovers(city: City, perCityThreshold: Int): Boolean {
        val civ = city.civ
        if (!civ.isCityState && WarStateAi.stateAllWars(civ) == StateAllWars.Losing) return false
        val numWorkers = numLandWorkers(civ)
        if (numWorkers >= civ.cities.size * 3 / 2 + 1) return false
        if (city.population.population < civ.gameInfo.ruleset.ai.define(AiDefine.AI_CITYSTRATEGY_WANT_TILE_IMPROVERS_MINIMUM_SIZE)) return false
        if (numWorkers < 1) return true

        var numResources = 0
        var numImproved = 0
        for (tile in city.tilesInRange) {
            if (tile.getOwner() != civ || tile.isWater) continue
            val resource = tile.tileResource ?: continue
            if (!civ.canSeeResource(resource)) continue
            // the first improvement that connects the resource
            val improvement = resource.getImprovements().firstNotNullOfOrNull { civ.gameInfo.ruleset.tileImprovements[it] } ?: continue
            if (!tile.improvementFunctions.canBuildImprovement(improvement, civ.state)) {
                if (tile.improvement == improvement.name) {
                    numImproved++
                    numResources++
                }
                continue
            }
            numResources++
        }
        val manyUnimproved = 2 * (numResources - numImproved) > numResources
        var multiplier = civ.cities.size + countCitiesFeatureSurrounded(civ)
        if (manyUnimproved) multiplier += 1
        multiplier += numSettlers(civ, includeWater = false)
        if (numWorkers * 100 < perCityThreshold * multiplier)
            return civ.stats.statsForNextTurn.gold > -10
        return false
    }

    fun enoughTileImprovers(city: City, perCityThreshold: Int): Boolean {
        val civ = city.civ
        if (city.ai.isCityStrategyActive(NEED_TILE_IMPROVERS)) return false
        val numBuilders = numLandWorkers(civ)
        if (civ.isCityState && numBuilders >= civ.cities.size) return true
        val moddedNumCities = civ.cities.size + countCitiesFeatureSurrounded(civ)
        return numBuilders * 100 >= perCityThreshold * moddedNumCities
    }

    /** At least the threshold percent of the city's owned workable tiles are water that is not a lake */
    fun needNavalGrowth(city: City, threshold: Int): Boolean {
        var oceanTiles = 0
        var workableTiles = 0
        for (tile in city.tilesInRange) {
            if (tile.getOwner() != city.civ || tile.isCityCenter()) continue
            workableTiles++
            if (tile.isWater && !tile.getBaseTerrain().isFreshwater) oceanTiles++
        }
        return workableTiles > 0 && oceanTiles * 100 / workableTiles >= threshold
    }

    /** More unimproved water resources the city can work than sea workers (on the map or being trained) */
    fun needNavalTileImprovement(city: City): Boolean {
        var unimproved = 0
        for (tile in city.tilesInRange)
            if (tile.isWater && tile.getCity() == city && tile.resource != null && tile.improvement == null) unimproved++
        return unimproved > numSeaWorkers(city.civ)
    }

    fun isCapitalNeedSettler(city: City, weightThreshold: Int): Boolean {
        if (!city.isCapital()) return false
        val civ = city.civ
        if (civ.isCityState) return false
        val citiesPlusSettlers = civ.cities.size + numSettlers(civ, includeWater = true)
        if (citiesPlusSettlers >= 3) return false
        val turn = civ.gameInfo.turns
        if (turn > 50 && city.ai.isCityStrategyActive(CAPITAL_UNDER_THREAT)) return false
        if (civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_WAR_MOBILIZATION")) return false
        return (citiesPlusSettlers == 1 && turn * 4 > weightThreshold) || (citiesPlusSettlers == 2 && turn * 2 > weightThreshold)
    }

    fun isFirstCultureBuilding(city: City): Boolean {
        val culture = baseYieldFromBuildings(city, Stat.Culture)
        return culture <= 0 || (city.isCapital() && culture <= 1)
    }

    fun isFirstCultureBuildingEmergency(city: City): Boolean =
        city.population.population >= 5 && baseYieldFromBuildings(city, Stat.Culture) <= 0

    fun isFirstScienceBuilding(city: City): Boolean =
        city.population.population >= 4 && yieldPerPopTimes100(city, Stat.Science) == 0

    fun isFirstGoldBuilding(city: City): Boolean =
        city.population.population >= 4 && buildingYieldModifier(city, Stat.Gold) == 0f

    fun isFirstProductionBuilding(city: City): Boolean =
        city.population.population >= 4 && baseYieldFromBuildings(city, Stat.Production) == 0 && buildingYieldModifier(city, Stat.Production) == 0f

    /** Religion personality above 6, or above 4 once the civilization has a pantheon; the city has 2+ citizens and no faith from buildings */
    fun isFirstFaithBuilding(city: City): Boolean {
        if (baseYieldFromBuildings(city, Stat.Faith) > 0) return false
        if (city.population.population < 2) return false
        val religionFlavor = city.civ.ai.personality(Flavor.Religion)
        return (religionFlavor > 4 && city.civ.religionManager.religion != null) || religionFlavor > 6
    }

    /** CvCityCitizens::IsAnyPlotBlockaded: a water tile of the city with a military unit of an enemy on water within 2 tiles
     *  (the original also requires the same water area, which Unciv does not track; only water tiles can share it with water) */
    fun isUnderBlockade(city: City): Boolean {
        val civ = city.civ
        val center = city.getCenterTile()
        val cache = turnCache(civ)
        val allEnemyTiles = cache.enemyNavalTiles ?: civ.getCivsAtWarWith().flatMap { enemy ->
            enemy.units.getCivUnits().filter { it.isMilitary() && it.currentTile.isWater }.map { it.currentTile }.toList()
        }.also { cache.enemyNavalTiles = it }
        val enemyTiles = allEnemyTiles.filter { it.aerialDistanceTo(center) <= 5 }
        if (enemyTiles.isEmpty()) return false
        return city.tilesInRange.any { tile ->
            tile != center && tile.isWater && enemyTiles.any { it.aerialDistanceTo(tile) <= 2 }
        }
    }

    fun isMediumCityHighDifficulty(city: City): Boolean = city.population.population >= 5 && gameHandicapIndex(city.civ) > 4

    fun isHillCity(city: City): Boolean = countOwnedTilesWithin2(city) { it.isHill() } > 1
    fun isForestCity(city: City): Boolean = countOwnedTilesWithin2(city) { Constants.forest in it.terrainFeatures } > 1
    fun isJungleCity(city: City): Boolean = countOwnedTilesWithin2(city) { Constants.jungle in it.terrainFeatures } > 1
    fun isMountainCity(city: City): Boolean = city.getCenterTile().neighbors.any { it.baseTerrain == Constants.mountain }

    /** CityStrategyAIHelpers::IsTestCityStrategy_GoodGPCity: 8+ Great Person points per turn from specialists and buildings with their modifiers */
    fun isGoodGreatPersonCity(city: City): Boolean = city.getGreatPersonPoints().sumValues() >= 8

    /**
     *  CvCityCulture::GetSlotTypeFirstAvailableCultureBuilding: the slot type of the cheapest building with Great Work slots, not a world wonder,
     *  that the city does not have and can build now; null if none.
     */
    fun firstAvailableCultureBuildingSlotType(city: City): String? {
        var cheapest: Building? = null
        for (building in city.civ.gameInfo.ruleset.buildings.values) {
            if (building.greatWorkSlotTypes.isEmpty() || building.isWonder) continue
            if (cheapest != null && building.cost >= cheapest.cost) continue
            if (city.cityConstructions.isBuilt(building.name)) continue
            if (!building.isBuildable(city.cityConstructions)) continue
            cheapest = building
        }
        return cheapest?.greatWorkSlotTypes?.first()?.name
    }

    /** Empty Great Work slots of [slotType] over all cities (CvPlayerCulture::GetNumGreatWorkSlots(eSlotType)) */
    fun numAvailableGreatWorkSlots(civ: Civilization, slotType: String): Int =
        civ.cities.sumOf { city -> city.greatWorks.getSlots().count { it.getType().name == slotType && it.isFree() } }

    fun needCultureBuilding(city: City): Boolean {
        val slotType = firstAvailableCultureBuildingSlotType(city) ?: return false
        return numAvailableGreatWorkSlots(city.civ, slotType) <= 2
    }

    /** Culture of the world wonders, worked natural wonders and worked improvements plus the base tourism of the city above 10 */
    fun needTourismBuilding(city: City): Boolean {
        var value = 0
        val buildingStats = city.cityStats.baseStatTree.children["Buildings"]
        for (building in city.cityConstructions.getBuiltBuildings())
            if (building.isWonder) value += buildingStats?.children?.get(building.name)?.totalStats?.culture?.toInt() ?: 0
        for (tile in city.getWorkedTiles())
            if (tile.isNaturalWonder() || tile.improvement != null) value += tile.stats.getTileStats(city, city.civ).culture.toInt()
        value += city.civ.culture.getCityTourism(city)
        return value > 10
    }

    /** The capital, a city on another landmass than the capital, or one more than 20 tiles away from it */
    fun isGoodAirliftCity(city: City): Boolean {
        if (city.isCapital()) return true
        val capital = city.civ.getCapital() ?: return false
        if (city.getCenterTile().getContinent() != capital.getCenterTile().getContinent()) return true
        return city.getCenterTile().aerialDistanceTo(capital.getCenterTile()) > 20
    }
    //endregion

    /** Registers the city strategy tests (the CvCityStrategyAI::DoTurn dispatch); thresholds as in the original: XML WeightThreshold plus
     *  [CivAi.weightThresholdModifier] where the original adds GetWeightThresholdModifier */
    internal fun registerAll(tests: StrategyTests) {
        tests.registerCity("AICITYSTRATEGY_TINY_CITY") { city, _ -> isTinyCity(city) }
        tests.registerCity("AICITYSTRATEGY_SMALL_CITY") { city, _ -> isSmallCity(city) }
        tests.registerCity("AICITYSTRATEGY_MEDIUM_CITY") { city, _ -> isMediumCity(city) }
        tests.registerCity("AICITYSTRATEGY_LARGE_CITY") { city, _ -> isLargeCity(city) }
        tests.registerCity("AICITYSTRATEGY_LANDLOCKED") { city, _ -> !city.isCoastal() }
        tests.registerCity(NEED_TILE_IMPROVERS) { city, def -> needTileImprovers(city, def.weightThreshold) }
        tests.registerCity("AICITYSTRATEGY_WANT_TILE_IMPROVERS") { city, def ->
            wantTileImprovers(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity("AICITYSTRATEGY_ENOUGH_TILE_IMPROVERS") { city, def ->
            enoughTileImprovers(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity("AICITYSTRATEGY_NEED_NAVAL_GROWTH") { city, def ->
            needNavalGrowth(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity(NEED_NAVAL_TILE_IMPROVEMENT) { city, _ -> needNavalTileImprovement(city) }
        tests.registerCity("AICITYSTRATEGY_ENOUGH_NAVAL_TILE_IMPROVEMENT") { city, _ -> !city.ai.isCityStrategyActive(NEED_NAVAL_TILE_IMPROVEMENT) }
        tests.registerCity("AICITYSTRATEGY_NEED_IMPROVEMENT_FOOD") { city, _ -> deficientYieldThisTurn(city) == Stat.Food }
        tests.registerCity("AICITYSTRATEGY_NEED_IMPROVEMENT_PRODUCTION") { city, _ -> deficientYieldThisTurn(city) == Stat.Production }
        tests.registerCity("AICITYSTRATEGY_HAVE_TRAINING_FACILITY") { city, _ -> landFreeExperience(city) > 0 }
        tests.registerCity("AICITYSTRATEGY_CAPITAL_NEED_SETTLER") { city, def ->
            isCapitalNeedSettler(city, def.weightThreshold + city.civ.ai.weightThresholdModifier(def))
        }
        tests.registerCity("AICITYSTRATEGY_FIRST_CULTURE_BUILDING_EMERGENCY") { city, _ -> isFirstCultureBuildingEmergency(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_CULTURE_BUILDING") { city, _ -> isFirstCultureBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_SCIENCE_BUILDING") { city, _ -> isFirstScienceBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_GOLD_BUILDING") { city, _ -> isFirstGoldBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_FAITH_BUILDING") { city, _ -> isFirstFaithBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_FIRST_PRODUCTION_BUILDING") { city, _ -> isFirstProductionBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_UNDER_BLOCKADE") { city, _ -> isUnderBlockade(city) }
        tests.registerCity("AICITYSTRATEGY_IS_PUPPET") { city, _ -> city.isPuppet }
        tests.registerCity("AICITYSTRATEGY_MEDIUM_CITY_HIGH_DIFFICULTY") { city, _ -> isMediumCityHighDifficulty(city) }
        tests.registerCity("AICITYSTRATEGY_ORIGINAL_CAPITAL") { city, _ -> city.isOriginalCapital }
        tests.registerCity("AICITYSTRATEGY_RIVER_CITY") { city, _ -> city.getCenterTile().isAdjacentToRiver() }
        tests.registerCity("AICITYSTRATEGY_MOUNTAIN_CITY") { city, _ -> isMountainCity(city) }
        tests.registerCity("AICITYSTRATEGY_FOREST_CITY") { city, _ -> isForestCity(city) }
        tests.registerCity("AICITYSTRATEGY_HILL_CITY") { city, _ -> isHillCity(city) }
        tests.registerCity("AICITYSTRATEGY_JUNGLE_CITY") { city, _ -> isJungleCity(city) }
        tests.registerCity("AICITYSTRATEGY_COAST_CITY") { city, _ -> city.isCoastal() }
        tests.registerCity("AICITYSTRATEGY_GOOD_GP_CITY") { city, _ -> isGoodGreatPersonCity(city) }
        tests.registerCity("AICITYSTRATEGY_NEED_CULTURE_BUILDING") { city, _ -> needCultureBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_NEED_TOURISM_BUILDING") { city, _ -> needTourismBuilding(city) }
        tests.registerCity("AICITYSTRATEGY_GOOD_AIRLIFT_CITY") { city, _ -> isGoodAirliftCity(city) }
    }
}
