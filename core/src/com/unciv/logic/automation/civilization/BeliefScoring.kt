package com.unciv.logic.automation.civilization

import com.unciv.logic.MultiFilter
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min

/**
 *  The fields of the original's CvBeliefEntry that CvReligionAI's belief scoring reads, derived from a belief's uniques for one civilization
 *  (the building of a building class is the civilization's own, as getCivilizationBuildings).
 *
 *  Unciv expresses each belief effect as a unique; [BeliefAiProfile.of] maps every unique type that carries one of the original's fields
 *  (the BNW beliefs use all of them). "Yields" are the original's yield types: every [Stat] but Happiness, which has its own fields.
 */
class BeliefAiProfile private constructor() {
    // CvBeliefEntry::GetMinPopulation / GetMinFollowers - from the `<in cities with at least [n] [Population / Followers ...]>` conditionals
    var minPopulation = 0
    var minFollowers = 0

    // ScoreBeliefAtPlot: terrain, feature, natural wonder and resource yield changes (tile filter -> summed yield),
    // "tiles without" variants, and improvement yield changes (counted twice on resource plots that can take the improvement)
    val tileYields = ArrayList<Pair<String, Int>>()
    val tileWithoutYields = ArrayList<Triple<String, String, Int>>()
    val improvementYields = ArrayList<Pair<List<TileImprovement>, Int>>()

    // ScoreBeliefAtCity
    var cityGrowthModifier = 0
    var requiresPeace = false
    var plotCultureCostModifier = 0
    var cityRangeStrikeModifier = 0
    var friendlyHealChange = 0
    var wonderProductionModifier = 0
    /** Era index + 1 of the last era whose wonders the modifier covers (CvBeliefEntry::GetObsoleteEra); 0 = never obsolete */
    var obsoleteEra = 0
    var riverHappiness = 0
    var happinessPerCity = 0
    /** Building class (as the civilization's building) -> happiness (Belief_BuildingClassHappiness) */
    val buildingHappiness = LinkedHashMap<Building, Int>()
    val cityYield = Stats()
    val tradeRouteYield = Stats()
    val specialistYield = Stats()
    /** Building class (as the civilization's building) -> yields (Belief_BuildingClassYieldChanges) */
    val buildingYields = LinkedHashMap<Building, Stats>()
    val worldWonderYield = Stats()
    val maxYieldModifierPerFollower = Stats()

    // ScoreBeliefForPlayer
    var faithFromKills = 0
    var maxDistance = 0
    var playerHappiness = 0
    var playerCultureModifier = 0
    /** Building classes made purchasable with Faith (Belief_BuildingClassFaithPurchase), as the civilization's buildings */
    val enabledBuildings = LinkedHashSet<Building>()
    /** Number of eras in which military units can be bought with Faith (Belief_EraFaithUnitPurchase) */
    var faithUnitPurchaseEras = 0
    var happinessPerFollowingCity = 0f
    var happinessPerXPeacefulForeignFollowers = 0
    var sciencePerOtherReligionFollower = 0
    var goldPerFollowingCity = 0f
    var goldPerXFollowers = 0
    var goldWhenCityAdopts = 0
    var cityStateMinimumInfluence = 0
    val yieldPerForeignCity = Stats()
    /** Followers per point of the yield (Belief_YieldChangePerXForeignFollowers), 0 = none */
    val yieldPerXForeignFollowers = Stats()
    var greatPersonExpendedFaith = 0
    var friendlyCityStateSpreadModifier = 0
    var combatModifierFriendlyCities = 0
    var combatModifierEnemyCities = 0
    var spreadDistanceModifier = 0
    var spreadStrengthModifier = 0
    var missionaryStrengthModifier = 0
    var missionaryCostModifier = 0
    var prophetStrengthModifier = 0
    var prophetCostModifier = 0
    var faithPurchaseAllGreatPeople = false
    var convertsBarbarians = false
    var cityStateInfluenceModifier = 0
    var inquisitorPressureRetention = 0
    var spyPressure = 0
    var faithBuildingTourism = 0

    companion object {
        private val worldWonderFilters = setOf("Wonder", "Wonders", "World Wonder", "World")

        private fun Stats.yieldSum(): Int = (this.sum() - this[Stat.Happiness]).toInt()

        private fun Stats.addYields(other: Stats, divisor: Float = 1f) {
            for ((stat, value) in other) if (stat != Stat.Happiness) add(stat, value / divisor)
        }

        private fun isForeignFilter(cityFilter: String) = cityFilter.contains("foreign", ignoreCase = true)

        fun of(belief: Belief, civ: Civilization): BeliefAiProfile {
            val profile = BeliefAiProfile()
            val builder = Builder(civ, profile)
            for (unique in belief.uniqueObjects) builder.add(unique)
            return profile
        }
    }

    private class Builder(val civ: Civilization, val profile: BeliefAiProfile) {
        val ruleset: Ruleset = civ.gameInfo.ruleset
        val state: GameContext = civ.state
        val prophet = civ.religionManager.getGreatProphetEquivalent()
        val missionary = ReligionAutomation.missionaryUnit(civ)

        /** The building classes matched by [filter], as this civilization's buildings */
        fun buildingClasses(filter: String): List<Building> =
            ruleset.buildings.values.asSequence()
                .filter { it.matchesFilter(filter, state) }
                .map { ruleset.buildings[it.replaces] ?: it }
                .distinct()
                .map { civ.getEquivalentBuilding(it) }
                .toList()

        @Readonly
        fun isEraComponent(filter: String) =
            filter == "any era" || filter.startsWith("pre-[") || filter.startsWith("post-[") || ruleset.eras.containsKey(filter)

        /** Belief_EraFaithUnitPurchase: the eras whose military units [unitFilter] lets buy with Faith */
        fun addFaithUnitPurchase(unitFilter: String, eras: MutableSet<String>) {
            val units = ruleset.units.values.filter { unit ->
                MultiFilter.multiFilter(unitFilter, { isEraComponent(it) || unit.matchesFilter(it, state, multiFilter = false) })
            }
            if (units.any { it.isGreatPerson }) profile.faithPurchaseAllGreatPeople = true
            if (units.none { it.isMilitary }) return
            for (era in ruleset.eras.values)
                if (MultiFilter.multiFilter(unitFilter, { !isEraComponent(it) || era.matchesSingleFilter(it, state) }))
                    eras.add(era.name)
        }

        val purchaseEras = HashSet<String>()

        fun add(unique: Unique) {
            for (modifier in unique.getModifiers(UniqueType.ConditionalPopulationFilter)) {
                val amount = modifier.params[0].toInt()
                val filter = modifier.params[1]
                when {
                    filter == "Population" -> profile.minPopulation = max(profile.minPopulation, amount)
                    filter.contains("Followers") -> profile.minFollowers = max(profile.minFollowers, amount)
                }
            }
            val bySpecialist = unique.getModifiers(UniqueType.ConditionalPopulationFilter)
                .any { it.params[1] == "Specialists" || ruleset.specialists.containsKey(it.params[1]) }

            when (unique.type) {
                UniqueType.StatsFromObject -> addStatsFromObject(unique.params[1], unique.stats)
                UniqueType.StatsFromTilesWithout ->
                    profile.tileWithoutYields.add(Triple(unique.params[1], unique.params[2], unique.stats.yieldSum()))
                UniqueType.StatsFromNamedSpecialist -> profile.specialistYield.addYields(unique.stats)
                UniqueType.StatsPerCity ->
                    if (bySpecialist) profile.specialistYield.addYields(unique.stats)
                    else {
                        profile.happinessPerCity += unique.stats.happiness.toInt()
                        profile.cityYield.addYields(unique.stats)
                    }
                UniqueType.StatsFromCitiesOnSpecificTiles -> {
                    profile.riverHappiness += unique.stats.happiness.toInt()
                    profile.cityYield.addYields(unique.stats)
                }
                UniqueType.Stats -> profile.playerHappiness += unique.stats.happiness.toInt()
                UniqueType.StatPercentBonus ->
                    if (unique.params[1] == Stat.Culture.name) profile.playerCultureModifier += unique.params[0].toInt()
                UniqueType.StatsFromTradeRoute -> profile.tradeRouteYield.addYields(unique.stats)
                UniqueType.StatPercentFromReligionFollowers -> {
                    val stat = Stat.safeValueOf(unique.params[1]) ?: return
                    profile.maxYieldModifierPerFollower[stat] = unique.params[2].toFloat()
                }
                UniqueType.GrowthPercentBonus -> {
                    profile.cityGrowthModifier += unique.params[0].toInt()
                    if (unique.getModifiers(UniqueType.ConditionalNotWar).isNotEmpty()) profile.requiresPeace = true
                }
                UniqueType.BorderGrowthPercentage -> profile.plotCultureCostModifier += unique.params[0].toInt()
                UniqueType.StrengthForCities -> profile.cityRangeStrikeModifier += unique.params[0].toInt()
                UniqueType.CityHealingUnits -> profile.friendlyHealChange += unique.params[1].toInt()
                UniqueType.PercentProductionWonders -> {
                    profile.wonderProductionModifier = max(profile.wonderProductionModifier, unique.params[0].toInt())
                    val era = ruleset.eras[unique.params[1]]
                    if (era != null) profile.obsoleteEra = max(profile.obsoleteEra, era.eraNumber + 1)
                }
                UniqueType.KillUnitPlunderNearCity -> {
                    profile.faithFromKills += unique.params[0].toInt()
                    profile.maxDistance = 4 // fixed in the unique's text
                }
                UniqueType.BuyBuildingsWithStat, UniqueType.BuyBuildingsByProductionCost ->
                    if (unique.params[1] == Stat.Faith.name) profile.enabledBuildings.addAll(buildingClasses(unique.params[0]))
                UniqueType.BuyBuildingsForAmountStat, UniqueType.BuyBuildingsIncreasingCost ->
                    if (unique.params[2] == Stat.Faith.name) profile.enabledBuildings.addAll(buildingClasses(unique.params[0]))
                UniqueType.BuyUnitsWithStat, UniqueType.BuyUnitsByProductionCost ->
                    if (unique.params[1] == Stat.Faith.name) addFaithUnitPurchase(unique.params[0], purchaseEras)
                UniqueType.BuyUnitsForAmountStat, UniqueType.BuyUnitsIncreasingCost ->
                    if (unique.params[2] == Stat.Faith.name) addFaithUnitPurchase(unique.params[0], purchaseEras)
                UniqueType.RestingPointOfCityStatesFollowingReligionChange, UniqueType.CityStateRestingPoint ->
                    profile.cityStateMinimumInfluence += unique.params[0].toInt()
                UniqueType.StatsFromGlobalCitiesFollowingReligion -> addPerFollowingCity(unique.stats, 1f, foreign = false)
                UniqueType.StatsFromGlobalCitiesFollowingReligionPer ->
                    addPerFollowingCity(unique.stats, unique.params[1].toFloat(), isForeignFilter(unique.params[2]))
                UniqueType.StatsFromGlobalFollowers -> addPerFollowers(unique.stats, unique.params[1].toInt(), isForeignFilter(unique.params[2]))
                UniqueType.StatsWhenSpreading -> profile.sciencePerOtherReligionFollower += unique.params[0].toInt()
                UniqueType.StatsWhenAdoptingReligion -> profile.goldWhenCityAdopts += unique.stats.gold.toInt()
                UniqueType.OneTimeGainStat ->
                    if (unique.params[1] == Stat.Faith.name && unique.getModifiers(UniqueType.TriggerUponExpendingUnit).isNotEmpty())
                        profile.greatPersonExpendedFaith += unique.params[0].toInt()
                UniqueType.NaturalReligionSpreadStrength ->
                    if (unique.params[1].contains("City-State"))
                        profile.friendlyCityStateSpreadModifier = max(profile.friendlyCityStateSpreadModifier, unique.params[0].toInt())
                    else profile.spreadStrengthModifier = max(profile.spreadStrengthModifier, unique.params[0].toInt())
                UniqueType.Strength -> {
                    val tiles = unique.getModifiers(UniqueType.ConditionalFightingInTiles).firstOrNull()?.params?.get(0) ?: return
                    when {
                        tiles.contains("Enemy") -> profile.combatModifierEnemyCities += unique.params[0].toInt()
                        tiles.contains("Friendly") -> profile.combatModifierFriendlyCities += unique.params[0].toInt()
                    }
                }
                UniqueType.ReligionSpreadDistance ->
                    profile.spreadDistanceModifier += unique.params[0].toInt() * 100 / civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_ADJACENT_CITY_DISTANCE)
                UniqueType.SpreadReligionStrength -> {
                    val unitFilter = unique.getModifiers(UniqueType.ConditionalOurUnit).firstOrNull()?.params?.get(0)
                    if (unitFilter != null && prophet != null && prophet.matchesFilter(unitFilter, state))
                        profile.prophetStrengthModifier += unique.params[0].toInt()
                    else profile.missionaryStrengthModifier += unique.params[0].toInt()
                }
                UniqueType.FaithCostOfGreatProphetChange -> profile.prophetCostModifier += unique.params[0].toInt()
                UniqueType.BuyUnitsDiscount -> if (unique.params[0] == Stat.Faith.name) {
                    if (missionary != null && missionary.matchesFilter(unique.params[1], state))
                        profile.missionaryCostModifier += unique.params[2].toInt()
                    else if (prophet != null && prophet.matchesFilter(unique.params[1], state))
                        profile.prophetCostModifier += unique.params[2].toInt()
                }
                UniqueType.ConvertAdjacentBarbarians -> profile.convertsBarbarians = true
                UniqueType.CityStateGoldGiftsProvideMoreInfluence -> profile.cityStateInfluenceModifier += unique.params[0].toInt()
                UniqueType.ReligionPressureRetention -> profile.inquisitorPressureRetention += unique.params[0].toInt()
                UniqueType.SpyReligionPressure -> profile.spyPressure += unique.params[0].toInt()
                UniqueType.FlatTourismFromFaithBuildings -> profile.faithBuildingTourism += unique.params[0].toInt()
                else -> {}
            }
            profile.faithUnitPurchaseEras = purchaseEras.size
        }

        /** "[stats] from every [object]": world wonders, specialists, improvements, terrain/feature/resource, buildings - in that order */
        fun addStatsFromObject(filter: String, stats: Stats) {
            when {
                filter in worldWonderFilters -> profile.worldWonderYield.addYields(stats)
                ruleset.specialists.containsKey(filter) -> profile.specialistYield.addYields(stats)
                ruleset.terrains.containsKey(filter) || ruleset.tileResources.containsKey(filter) ->
                    profile.tileYields.add(filter to stats.yieldSum())
                else -> {
                    val improvements = ruleset.tileImprovements.values.filter { it.matchesFilter(filter, state) }
                    if (improvements.isNotEmpty()) {
                        profile.improvementYields.add(improvements to stats.yieldSum())
                        return
                    }
                    val buildings = buildingClasses(filter)
                    if (buildings.isEmpty()) {
                        profile.tileYields.add(filter to stats.yieldSum())
                        return
                    }
                    for (building in buildings) {
                        if (stats.happiness != 0f)
                            profile.buildingHappiness[building] = (profile.buildingHappiness[building] ?: 0) + stats.happiness.toInt()
                        if (stats.yieldSum() != 0) profile.buildingYields.getOrPut(building) { Stats() }.addYields(stats)
                    }
                }
            }
        }

        /** HappinessPerFollowingCity, GoldPerFollowingCity, Belief_YieldChangePerForeignCity */
        fun addPerFollowingCity(stats: Stats, cities: Float, foreign: Boolean) {
            profile.happinessPerFollowingCity += stats.happiness / cities
            if (foreign) profile.yieldPerForeignCity.addYields(stats, cities)
            else {
                profile.goldPerFollowingCity += stats.gold / cities
                for ((stat, value) in stats)
                    if (stat != Stat.Happiness && stat != Stat.Gold) profile.yieldPerForeignCity.add(stat, value / cities)
            }
        }

        /** HappinessPerXPeacefulForeignFollowers, GoldPerXFollowers, Belief_YieldChangePerXForeignFollowers: followers per point */
        fun addPerFollowers(stats: Stats, followers: Int, foreign: Boolean) {
            for ((stat, value) in stats) {
                if (value <= 0f) continue
                val perPoint = (followers / value).toInt()
                when {
                    stat == Stat.Happiness -> profile.happinessPerXPeacefulForeignFollowers = perPoint
                    stat == Stat.Gold && !foreign -> profile.goldPerXFollowers = perPoint
                    else -> profile.yieldPerXForeignFollowers[stat] = perPoint.toFloat()
                }
            }
        }
    }
}

/**
 *  The data CvReligionAI::ScoreBelief computes over the map and the player once per decision: the plots it scores (revealed, unowned or
 *  ours, within 3 of one of our cities) with their multipliers, and the player-level inputs.
 */
class BeliefScoringContext(val civ: Civilization) {
    val plots: List<Tile>
    val plotMultipliers: IntArray
    /** CvReligionAI::ScoreBeliefAtCity: 3 if Offense×2 + Happiness − Defense > 15, 1 if < 6, else 2 */
    val happinessMultiplier: Int
    /** CvGame::getCurrentEra: the average era of the living players */
    val gameEra: Int
    /** CvReligionAI::ScoreBeliefForPlayer: 100 × enhanced religions / the most religions the game can have */
    val religionsEnhancedPercent: Int

    init {
        val ai = civ.gameInfo.ruleset.ai
        val worked = ai.define(AiDefine.RELIGION_BELIEF_SCORE_WORKED_PLOT_MULTIPLIER)
        val owned = ai.define(AiDefine.RELIGION_BELIEF_SCORE_OWNED_PLOT_MULTIPLIER)
        val unowned = ai.define(AiDefine.RELIGION_BELIEF_SCORE_UNOWNED_PLOT_MULTIPLIER)
        val tiles = LinkedHashSet<Tile>()
        for (city in civ.cities)
            for (tile in city.getCenterTile().getTilesInDistance(3)) {
                val owner = tile.getOwner()
                if ((owner == null || owner == civ) && tile.isExplored(civ)) tiles.add(tile)
            }
        plots = tiles.toList()
        plotMultipliers = IntArray(plots.size) { i ->
            val tile = plots[i]
            when {
                tile.isWorked() -> worked
                tile.getOwner() != null -> owned
                else -> unowned
            }
        }

        val needFactor = civ.ai.personality(Flavor.Offense) * 2 + civ.ai.personality(Flavor.Happiness) - civ.ai.personality(Flavor.Defense)
        happinessMultiplier = when {
            needFactor > 15 -> 3
            needFactor < 6 -> 1
            else -> 2
        }

        val living = civ.gameInfo.civilizations.filter { it.isAlive() && !it.isSpectator }
        gameEra = if (living.isEmpty()) 0 else living.sumOf { it.getEraNumber() } / living.size

        val enhanced = civ.gameInfo.civilizations.count { it.religionManager.religionState == ReligionState.EnhancedReligion }
        religionsEnhancedPercent = 100 * enhanced / max(1, civ.religionManager.maxNumberOfReligions())
    }
}

/** CvReligionAI's belief scoring (CvReligionClasses.cpp) */
object BeliefScoring {

    /** CvReligionAI::ScoreBelief: 5, plus the plot scores × worked/owned/unowned multiplier, plus the city scores × the city multiplier,
     *  plus the player score; halved for a pantheon belief */
    fun scoreBelief(belief: Belief, context: BeliefScoringContext): Int {
        val civ = context.civ
        val profile = BeliefAiProfile.of(belief, civ)
        var score = 5
        for (i in context.plots.indices)
            score += scoreBeliefAtPlot(profile, context.plots[i], civ) * context.plotMultipliers[i]
        val cityMultiplier = civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_BELIEF_SCORE_CITY_MULTIPLIER)
        for (city in civ.cities) score += scoreBeliefAtCity(profile, city, context) * cityMultiplier
        score += scoreBeliefForPlayer(profile, context)
        if (belief.type == BeliefType.Pantheon) score /= 2
        return score
    }

    /** CvReligionAI::ScoreBeliefAtPlot: the belief's yield changes on this plot; improvement changes count twice, and only on a resource plot
     *  that could take the improvement */
    fun scoreBeliefAtPlot(profile: BeliefAiProfile, tile: Tile, civ: Civilization): Int {
        var value = 0
        for ((filter, yields) in profile.tileYields)
            if (tile.matchesFilter(filter, civ)) value += yields
        for ((filter, without, yields) in profile.tileWithoutYields)
            if (tile.matchesFilter(filter, civ) && !tile.matchesFilter(without, civ)) value += yields
        if (tile.resource != null)
            for ((improvements, yields) in profile.improvementYields)
                for (improvement in improvements)
                    if (tile.improvementFunctions.canImprovementBeBuiltHere(improvement)) value += yields * 2
        return value
    }

    /** CvReligionAI::ScoreBeliefAtCity */
    fun scoreBeliefAtCity(profile: BeliefAiProfile, city: City, context: BeliefScoringContext): Int {
        val civ = context.civ
        val population = city.population.population
        val happinessMultiplier = context.happinessMultiplier
        val minPop = profile.minPopulation
        val minFollowers = profile.minFollowers

        var value = profile.cityGrowthModifier / 3
        if (profile.requiresPeace) value /= 2
        value += (-profile.plotCultureCostModifier * 2) / 10
        value += (profile.cityRangeStrikeModifier / 10) * max(civ.ai.personality(Flavor.Defense), civ.ai.personality(Flavor.CityDefense))
        value += profile.friendlyHealChange / 10

        if (profile.obsoleteEra > 0) {
            if (profile.obsoleteEra > context.gameEra) value += profile.wonderProductionModifier * profile.obsoleteEra / 10
        } else value += profile.wonderProductionModifier / 3

        var temp = profile.riverHappiness * happinessMultiplier
        if (minPop > 0 && population >= minPop) temp *= 2
        value += temp

        temp = profile.happinessPerCity * happinessMultiplier
        if (minPop > 0 && population >= minPop) temp *= 2
        value += temp

        for (happiness in profile.buildingHappiness.values) {
            temp = happiness * happinessMultiplier
            if (minFollowers > 0 && population >= minFollowers) temp *= 2
            value += temp
        }

        for (stat in Stat.entries) {
            if (stat == Stat.Happiness) continue
            temp = profile.cityYield[stat].toInt()
            if (minPop > 0 && population >= minPop) temp *= 2
            value += temp

            temp = profile.tradeRouteYield[stat].toInt()
            if (minPop > 0 && population >= minPop) temp *= 2
            if (city.isConnectedToCapital()) temp *= 2
            value += temp

            temp = profile.specialistYield[stat].toInt()
            if (population >= 8) temp *= 3
            value += temp

            for ((building, yields) in profile.buildingYields) {
                temp = yields[stat].toInt()
                if (minFollowers > 0 && population < minFollowers) temp /= 2
                if (city.cityConstructions.isBuilt(building.name)) temp *= 2
                if (building.isNationalWonder) temp /= 2
                value += temp
            }

            value += profile.worldWonderYield[stat].toInt() * 3 / 2

            val perFollower = profile.maxYieldModifierPerFollower[stat].toInt()
            if (perFollower > 0) value += min(perFollower, population) / 2
        }
        return value
    }

    /** CvReligionAI::ScoreBeliefForPlayer: the personality flavors (Offense, Defense, Happiness, Culture, Gold, Great People, Science,
     *  Diplomacy, Expansion), world conquest, the share of enhanced religions and the barbarian strategy */
    fun scoreBeliefForPlayer(profile: BeliefAiProfile, context: BeliefScoringContext): Int {
        val civ = context.civ
        val ai = civ.ai
        val offense = ai.personality(Flavor.Offense)
        val defense = ai.personality(Flavor.Defense)
        val happiness = ai.personality(Flavor.Happiness)
        val culture = ai.personality(Flavor.Culture)
        val gold = ai.personality(Flavor.Gold)
        val greatPeople = ai.personality(Flavor.GreatPeople)
        val science = ai.personality(Flavor.Science)
        val diplomacy = ai.personality(Flavor.Diplomacy)
        val expansion = ai.personality(Flavor.Expansion)
        val worldConquest = ai.isGoingForWorldConquest()

        var value = 0
        // Pantheon
        if (profile.faithFromKills > 0) {
            var temp = profile.faithFromKills * profile.maxDistance * offense / 100
            if (worldConquest) temp *= 2
            value += temp
        }
        value += profile.playerHappiness * happiness
        value += profile.playerCultureModifier * culture

        // Follower: unlocked buildings, faith-purchasable units per era
        val personality = ai.personalityFlavorVector()
        for (building in profile.enabledBuildings) {
            val flavors = civ.gameInfo.ruleset.ai.flavorsOf(building)
            val producible = building.cost > 0 && !building.hasUnique(UniqueType.Unbuildable, GameContext.IgnoreConditionals)
            for (i in personality.indices) {
                var flavorValue = personality[i] * flavors[i]
                if (producible) flavorValue /= 10
                value += flavorValue
            }
        }
        if (profile.faithUnitPurchaseEras > 0) {
            var temp = offense + defense / 2
            if (worldConquest) temp *= 2
            value += temp * profile.faithUnitPurchaseEras
        }

        // Founder
        value += (profile.happinessPerFollowingCity * (happiness + 2 * offense - defense).toFloat()).toInt()
        if (profile.happinessPerXPeacefulForeignFollowers > 0 && defense > 0)
            value += (happiness * 10 * defense) / (profile.happinessPerXPeacefulForeignFollowers * defense)
        value += science * profile.sciencePerOtherReligionFollower / 5
        value += (profile.goldPerFollowingCity * gold).toInt()
        if (profile.goldPerXFollowers > 0) value += gold * 4 / profile.goldPerXFollowers
        value += gold * profile.goldWhenCityAdopts / 50
        value += diplomacy * profile.cityStateMinimumInfluence / 7
        for (stat in Stat.entries) {
            if (stat == Stat.Happiness) continue
            val perCity = profile.yieldPerForeignCity[stat].toInt()
            val perFollowers = profile.yieldPerXForeignFollowers[stat].toInt()
            if (stat == Stat.Culture) {
                value += perCity * 2 * culture
                if (perFollowers > 0) value += 10 * culture / perFollowers
            } else {
                value += perCity * 10
                if (perFollowers > 0) value += 50 / perFollowers
            }
        }

        // Enhancer
        value += greatPeople * profile.greatPersonExpendedFaith / 10
        value += diplomacy * profile.friendlyCityStateSpreadModifier / 20
        value += defense * profile.combatModifierFriendlyCities / 4
        value += offense * profile.combatModifierEnemyCities / 4

        // Timing: the share of religions already enhanced (these flavor addends apply to every belief, as in the original)
        val enhancedPercent = context.religionsEnhancedPercent
        when {
            enhancedPercent < 33 -> {
                value += 2 * expansion + profile.spreadDistanceModifier
                value += 2 * science + profile.spreadStrengthModifier
                value += profile.missionaryStrengthModifier * 2
                value += -profile.missionaryCostModifier * 2
                value += profile.prophetStrengthModifier / 4
                value += -profile.prophetCostModifier / 4
            }
            enhancedPercent < 66 -> {
                value += expansion + profile.spreadDistanceModifier / 2
                value += science + profile.spreadStrengthModifier / 2
                value += profile.prophetStrengthModifier / 2
                value += -profile.prophetCostModifier / 2
                value += profile.missionaryStrengthModifier
                value += -profile.missionaryCostModifier
            }
            else -> {
                value += (2 * expansion + profile.spreadDistanceModifier) / 4
                value += (2 * science + profile.spreadStrengthModifier) / 4
                value += profile.missionaryStrengthModifier / 2
                value += -profile.missionaryCostModifier / 2
                value += profile.prophetStrengthModifier
                value += -profile.prophetCostModifier
            }
        }

        // Reformation
        if (profile.faithPurchaseAllGreatPeople) value += unfinishedGreatPersonBranches(civ) * 6
        if (profile.convertsBarbarians)
            value += if (ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")) 40 else 15
        if (profile.cityStateInfluenceModifier > 0) value += profile.cityStateInfluenceModifier * diplomacy / 5
        if (profile.inquisitorPressureRetention > 0) value += profile.inquisitorPressureRetention / 2
        if (profile.spyPressure > 0) value += profile.spyPressure * 5
        if (profile.faithBuildingTourism > 0) value += profile.faithBuildingTourism * 20
        return value
    }

    /** The policy branches whose finishers unlock a Great Person for Faith (the original names them), still unfinished */
    private val greatPersonBranches = listOf("Tradition", "Honor", "Aesthetics", "Commerce", "Exploration", "Rationalism")

    /** ScoreBeliefForPlayer, FaithPurchaseAllGreatPeople: the branches of [greatPersonBranches] not finished (a finished branch is never
     *  blocked in Unciv, so the original's "or blocked" adds nothing) */
    private fun unfinishedGreatPersonBranches(civ: Civilization): Int {
        val branches = civ.gameInfo.ruleset.policyBranches
        return greatPersonBranches.count { name ->
            val branch = branches[name] ?: return@count false
            !branch.policies.all { civ.policies.isAdopted(it.name) }
        }
    }
}
