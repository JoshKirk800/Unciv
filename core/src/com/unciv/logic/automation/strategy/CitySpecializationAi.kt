package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.CitySpecializationDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.automation.unit.CitySiteEvaluator
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats

/**
 *  The persisted state of a civilization's city specialization AI (CvCitySpecializationAI's serialized members), `civ.ai.citySpecialization`;
 *  m_iWonderCityID is [CivAi.wonderCityId] (shared with [WonderProductionAi]).
 *  Specializations are names of [com.unciv.models.ruleset.ai.CitySpecializationDef]s (CITYSPECIALIZATION_*), null = NO_CITY_SPECIALIZATION.
 */
class CitySpecializationState : IsPartOfGameInfoSerialization {
    /** m_bSpecializationsDirty */
    var specializationsDirty = false
    /** m_bInterruptWonders: a new war or a lost city - every city chooses its production again at the next evaluation */
    var interruptWonders = false
    /** m_eNextSpecializationDesired: the specialization the next city founded gets as its default */
    var nextSpecializationDesired: String? = null
    /** m_eNextWonderDesired: building name, null = none */
    var nextWonderDesired: String? = null
    /** m_iNextWonderWeight: total weight of the wonders ChooseWonder considered */
    var nextWonderWeight = 0
    /** m_iLastTurnEvaluated */
    var lastTurnEvaluated = 0

    // What the events that call CvCitySpecializationAI::SetSpecializationsDirty in the original are detected from, as seen last turn
    /** The civilizations this one was at war with (a new war: SPECIALIZATION_UPDATE_NOW_AT_WAR) */
    var warsLastTurn = HashSet<String>()
    /** The ids of this civilization's cities (a city lost to another civilization: MY_CITY_CAPTURED; a new one: CITY_FOUNDED / ENEMY_CITY_CAPTURED) */
    var citiesLastTurn = HashSet<String>()
    /** Techs researched (RESEARCH_COMPLETE) */
    var techsLastTurn = 0
    /** Active grand strategy (NEW_GRAND_STRATEGY) */
    var grandStrategyLastTurn: String? = null
    /** Active military strategies that have UpdateCitySpecializations (STRATEGY_NOW_ON / STRATEGY_NOW_OFF) */
    var militaryStrategiesLastTurn = HashSet<String>()

    fun clone(): CitySpecializationState {
        val toReturn = CitySpecializationState()
        toReturn.specializationsDirty = specializationsDirty
        toReturn.interruptWonders = interruptWonders
        toReturn.nextSpecializationDesired = nextSpecializationDesired
        toReturn.nextWonderDesired = nextWonderDesired
        toReturn.nextWonderWeight = nextWonderWeight
        toReturn.lastTurnEvaluated = lastTurnEvaluated
        toReturn.warsLastTurn.addAll(warsLastTurn)
        toReturn.citiesLastTurn.addAll(citiesLastTurn)
        toReturn.techsLastTurn = techsLastTurn
        toReturn.grandStrategyLastTurn = grandStrategyLastTurn
        toReturn.militaryStrategiesLastTurn.addAll(militaryStrategiesLastTurn)
        return toReturn
    }
}

/**
 *  CvCitySpecializationAI (CvCitySpecializationAI.cpp): which specializations an AI empire needs (one per city that is not a puppet, plus
 *  one for the next city) and which city gets which, including the wonder city.
 *
 *  The yields are indexed as the original's YieldTypes: food 0, production 1, gold 2, science 3, and [GENERAL] (NO_YIELD, general economic).
 *  Production specializations are told apart by their XML subtype (ProductionSpecializationSubtypes).
 */
object CitySpecializationAi {
    private const val FOOD = 0
    private const val PRODUCTION = 1
    private const val GOLD = 2
    private const val SCIENCE = 3
    private const val GENERAL = 4
    private val yieldStats = arrayOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science)

    const val SUBTYPE_MILITARY_TRAINING = "PRODUCTION_SPECIALIZATION_MILITARY_TRAINING"
    const val SUBTYPE_EMERGENCY_UNITS = "PRODUCTION_SPECIALIZATION_EMERGENCY_UNITS"
    const val SUBTYPE_MILITARY_NAVAL = "PRODUCTION_SPECIALIZATION_MILITARY_NAVAL"
    const val SUBTYPE_WONDER = "PRODUCTION_SPECIALIZATION_WONDER"
    const val SUBTYPE_SPACESHIP = "PRODUCTION_SPECIALIZATION_SPACESHIP"
    private val subtypes = arrayOf(SUBTYPE_MILITARY_TRAINING, SUBTYPE_EMERGENCY_UNITS, SUBTYPE_MILITARY_NAVAL, SUBTYPE_WONDER, SUBTYPE_SPACESHIP)

    /** One entry of a CvWeightedVector: [key] with a changeable weight */
    class Weighted<K>(val key: K, var weight: Int)

    /** CvWeightedVector::SortItems, highest weight first; ties keep their order */
    private fun <K> MutableList<Weighted<K>>.sortByWeight() = sortWith(compareByDescending { it.weight })

    /** The weights WeightSpecializations computes: by yield ([FOOD]..[SCIENCE], [GENERAL]) and by production subtype */
    class SpecializationWeights(val yields: MutableList<Weighted<Int>>, val subtypes: MutableList<Weighted<String>>)

    /** CvCitySpecializationAI::GetWonderBuildCity */
    fun wonderBuildCity(civ: Civilization): City? = WonderProductionAi.wonderBuildCity(civ)

    private fun rules(civ: Civilization): AiRuleset = civ.gameInfo.ruleset.ai
    private fun specializationDef(civ: Civilization, name: String?): CitySpecializationDef? =
        name?.let { n -> rules(civ).citySpecializations.firstOrNull { it.name == n } }

    /** GetWonderSpecialization */
    private fun wonderSpecialization(civ: Civilization): String? = rules(civ).citySpecializations.firstOrNull { it.isWonder }?.name
    /** GetEconomicDefaultSpecialization */
    private fun economicDefaultSpecialization(civ: Civilization): String? = rules(civ).citySpecializations.firstOrNull { it.isDefault }?.name

    private fun yieldIndexOf(def: CitySpecializationDef): Int = when (def.yieldType) {
        null -> GENERAL
        Stat.Food.name -> FOOD
        Stat.Production.name -> PRODUCTION
        Stat.Gold.name -> GOLD
        Stat.Science.name -> SCIENCE
        else -> -1
    }

    /** GetFirstSpecializationForYield */
    private fun firstSpecializationForYield(civ: Civilization, yieldIndex: Int): String? =
        rules(civ).citySpecializations.firstOrNull { yieldIndexOf(it) == yieldIndex }?.name

    /** GetNumSpecializationsForYield */
    private fun numSpecializationsForYield(civ: Civilization, yieldIndex: Int): Int = rules(civ).citySpecializations.count { yieldIndexOf(it) == yieldIndex }

    /**
     *  CvCitySpecializationAI::DoTurn. Not for humans, city-states, before AI_CITY_SPECIALIZATION_EARLIEST_TURN or below 2 cities. Re-evaluates
     *  when dirty or while `last evaluation + AI_CITY_SPECIALIZATION_REEVALUATION_INTERVAL > turn` - which, the last evaluation being set to the
     *  current turn each time, holds on every turn once evaluated, as in the original. An evaluation chooses the next wonder, weights and
     *  assigns the specializations, and after a new war or a lost city has every city but those training the escort of a settler's operation
     *  (CvCity::IsBuildingUnitForOperation) choose its production again.
     *
     *  Also, as CvCity::init does for a new city, a city without a default specialization yet gets [CitySpecializationState.nextSpecializationDesired].
     *  The events that make the original's specializations dirty are detected here by comparing with the previous turn.
     */
    fun doTurn(civ: Civilization) {
        if (civ.isHuman() || !civ.isMajorCiv) return
        val state = civ.ai.citySpecialization
        for (city in civ.cities) if (!city.ai.defaultSpecializationAssigned) {
            city.ai.defaultSpecialization = state.nextSpecializationDesired
            city.ai.defaultSpecializationAssigned = true
        }
        detectUpdates(civ, state)

        val rules = rules(civ)
        val turn = civ.gameInfo.turns
        if (turn < rules.define(AiDefine.AI_CITY_SPECIALIZATION_EARLIEST_TURN)) return
        if (civ.cities.size < 2) return
        if (!state.specializationsDirty && state.lastTurnEvaluated + rules.define(AiDefine.AI_CITY_SPECIALIZATION_REEVALUATION_INTERVAL) <= turn) return
        // The original has no specialization weights without a capital (and would then fail picking from them)
        if (civ.getCapital() == null) return

        val (wonder, wonderWeight) = WonderProductionAi.chooseWonder(civ, adjustForOtherPlayers = true)
        state.nextWonderDesired = wonder?.name
        state.nextWonderWeight = wonderWeight
        assignSpecializations(civ, weightSpecializations(civ))
        state.specializationsDirty = false
        state.lastTurnEvaluated = turn

        if (state.interruptWonders)
            for (city in civ.cities) if (!civ.ai.operations.isBuildingUnitForOperation(city))
                ConstructionAutomation(city.cityConstructions).chooseNextConstruction(interruptWonders = true)
        state.interruptWonders = false
    }

    /** CvCitySpecializationAI::SetSpecializationsDirty, from what changed since the last turn */
    private fun detectUpdates(civ: Civilization, state: CitySpecializationState) {
        val wars = civ.getCivsAtWarWith().filter { !it.isBarbarian }.mapTo(HashSet()) { it.civID }
        val cities = civ.cities.mapTo(HashSet()) { it.id }
        val techs = civ.tech.techsResearched.size
        val militaryStrategies = rules(civ).militaryStrategies.filter { it.updateCitySpecializations && civ.ai.isMilitaryStrategyActive(it.name) }
            .mapTo(HashSet()) { it.name }
        val newWar = wars.any { it !in state.warsLastTurn }
        val lostCity = state.citiesLastTurn.any { id -> id !in cities && civ.gameInfo.civilizations.any { other -> other != civ && other.cities.any { it.id == id } } }
        if (newWar || lostCity) state.interruptWonders = true
        if (newWar || lostCity || cities.any { it !in state.citiesLastTurn } || techs != state.techsLastTurn
            || civ.ai.grandStrategy != state.grandStrategyLastTurn || militaryStrategies != state.militaryStrategiesLastTurn)
            state.specializationsDirty = true
        state.warsLastTurn = wars
        state.citiesLastTurn = cities
        state.techsLastTurn = techs
        state.grandStrategyLastTurn = civ.ai.grandStrategy
        state.militaryStrategiesLastTurn = militaryStrategies
    }

    //region Weights

    /**
     *  CvCitySpecializationAI::WeightSpecializations, from the player's flavors (negative as 0):
     *  - food: AI_CITY_SPECIALIZATION_FOOD_WEIGHT_EARLY_EXPANSION under ECONOMICAISTRATEGY_EARLY_EXPANSION, + EXPANSION × _FLAVOR_EXPANSION,
     *    + the unowned percent of the capital's landmass × _PERCENT_CONTINENT_UNOWNED, + cities × _NUM_CITIES, + settlers × _NUM_SETTLERS;
     *    tripled with one city plus settlers in all; at least 0;
     *  - production: [weightProductionSubtypes];
     *  - gold: GOLD × _GOLD_WEIGHT_FLAVOR_GOLD + CvDiplomacyAI::GetTotalLandDisputeLevel × _GOLD_WEIGHT_LAND_DISPUTE;
     *  - science: SCIENCE × _SCIENCE_WEIGHT_FLAVOR_SCIENCE + SPACESHIP × _FLAVOR_SPACESHIP;
     *  - general economic: _GENERAL_ECONOMIC_WEIGHT;
     *  plus the active grand strategy's AIGrandStrategy_Yields for food, gold and science.
     */
    fun weightSpecializations(civ: Civilization): SpecializationWeights {
        val rules = rules(civ)
        fun flavor(f: Flavor) = civ.ai.flavor(f).coerceAtLeast(0)
        val flavorWonder = flavor(Flavor.Wonder)
        val flavorSpaceship = flavor(Flavor.Spaceship)

        var food = 0
        val capitalContinent = civ.getCapital()!!.getCenterTile().getContinent()
        var areaTiles = 0
        var unownedTiles = 0
        for (tile in civ.gameInfo.tileMap.values) {
            if (tile.getContinent() != capitalContinent) continue
            areaTiles++
            if (tile.getOwner() == null) unownedTiles++
        }
        val numCities = civ.cities.size
        val numSettlers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true, includeWater = true)
        if (civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_EARLY_EXPANSION"))
            food += rules.define(AiDefine.AI_CITY_SPECIALIZATION_FOOD_WEIGHT_EARLY_EXPANSION)
        food += flavor(Flavor.Expansion) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_FOOD_WEIGHT_FLAVOR_EXPANSION)
        if (areaTiles > 0) food += unownedTiles * 100 / areaTiles * rules.define(AiDefine.AI_CITY_SPECIALIZATION_FOOD_WEIGHT_PERCENT_CONTINENT_UNOWNED)
        food += numCities * rules.define(AiDefine.AI_CITY_SPECIALIZATION_FOOD_WEIGHT_NUM_CITIES)
        food += numSettlers * rules.define(AiDefine.AI_CITY_SPECIALIZATION_FOOD_WEIGHT_NUM_SETTLERS)
        if (numCities + numSettlers == 1) food *= 3
        if (food < 0) food = 0

        val subtypeWeights = weightProductionSubtypes(civ, flavorWonder, flavorSpaceship)
        val production = subtypeWeights.sumOf { it.weight }

        var gold = flavor(Flavor.Gold) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_GOLD_WEIGHT_FLAVOR_GOLD) +
            civ.ai.diplomacy.totalLandDisputeLevel() * rules.define(AiDefine.AI_CITY_SPECIALIZATION_GOLD_WEIGHT_LAND_DISPUTE)
        var science = flavor(Flavor.Science) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_SCIENCE_WEIGHT_FLAVOR_SCIENCE) +
            flavorSpaceship * rules.define(AiDefine.AI_CITY_SPECIALIZATION_SCIENCE_WEIGHT_FLAVOR_SPACESHIP)
        val general = rules.define(AiDefine.AI_CITY_SPECIALIZATION_GENERAL_ECONOMIC_WEIGHT)

        civ.ai.grandStrategyDef()?.let { gs ->
            food += gs.yields[Stat.Food.name] ?: 0
            gold += gs.yields[Stat.Gold.name] ?: 0
            science += gs.yields[Stat.Science.name] ?: 0
        }
        val yields = mutableListOf(Weighted(FOOD, food), Weighted(PRODUCTION, production), Weighted(GOLD, gold), Weighted(SCIENCE, science), Weighted(GENERAL, general))
        return SpecializationWeights(yields, subtypeWeights)
    }

    /**
     *  CvCitySpecializationAI::WeightProductionSubtypes:
     *  - military training: OFFENSE (personality and grand strategy) × _TRAINING_PER_OFFENSE + the WAR approach bias × _TRAINING_PER_PERSONALITY,
     *    + _WEIGHT_WAR_MOBILIZATION under MILITARYAISTRATEGY_WAR_MOBILIZATION;
     *  - emergency units: the civilizations at war with × _CIVS_AT_WAR_WITH, + _CAPITAL_THREAT if the capital runs AICITYSTRATEGY_CAPITAL_UNDER_THREAT,
     *    + _EMPIRE_DEFENSE / _EMPIRE_DEFENSE_CRITICAL under those strategies (units requested by operations × _OPERATIONAL_UNITS_REQUESTED:
     *    the military operations are not ported, so none are requested); both 0 under MILITARYAISTRATEGY_ENOUGH_MILITARY_UNITS;
     *  - naval: _NEED_NAVAL_UNITS (_CRITICAL) under those strategies, 0 under ENOUGH_NAVAL_UNITS;
     *  - wonder: min(WONDER × _FLAVOR_WONDER, the next wonders' total weight × _NEXT_WONDER), at least 0, halved under EMPIRE_DEFENSE_CRITICAL;
     *  - spaceship: SPACESHIP × _FLAVOR_SPACESHIP if spaceship parts can be built;
     *  plus the active grand strategy's production boost to military training (if it has OFFENSE flavor) or else to spaceship (if SPACESHIP).
     */
    fun weightProductionSubtypes(civ: Civilization, flavorWonder: Int, flavorSpaceship: Int): MutableList<Weighted<String>> {
        val rules = rules(civ)
        fun usingMilitary(name: String) = civ.ai.isMilitaryStrategyActive(name)
        var criticalDefense = false
        var militaryTraining = civ.ai.personalityAndGrandStrategy(Flavor.Offense) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_TRAINING_PER_OFFENSE) +
            civ.ai.majorCivApproachBias(MajorCivApproach.War) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_TRAINING_PER_PERSONALITY)
        var emergencyUnits = WarStateAi.numberCivsAtWarWith(civ) * rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_CIVS_AT_WAR_WITH)
        if (civ.getCapital()?.ai?.isCityStrategyActive("AICITYSTRATEGY_CAPITAL_UNDER_THREAT") == true)
            emergencyUnits += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_CAPITAL_THREAT)
        if (usingMilitary("MILITARYAISTRATEGY_WAR_MOBILIZATION"))
            militaryTraining += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_WAR_MOBILIZATION)
        if (usingMilitary("MILITARYAISTRATEGY_EMPIRE_DEFENSE"))
            emergencyUnits += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_EMPIRE_DEFENSE)
        if (usingMilitary("MILITARYAISTRATEGY_EMPIRE_DEFENSE_CRITICAL")) {
            criticalDefense = true
            emergencyUnits += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_EMPIRE_DEFENSE_CRITICAL)
        }
        if (usingMilitary("MILITARYAISTRATEGY_ENOUGH_MILITARY_UNITS")) {
            militaryTraining = 0
            emergencyUnits = 0
        }
        var naval = 0
        if (usingMilitary("MILITARYAISTRATEGY_NEED_NAVAL_UNITS")) naval += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_NEED_NAVAL_UNITS)
        if (usingMilitary("MILITARYAISTRATEGY_NEED_NAVAL_UNITS_CRITICAL")) naval += rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_NEED_NAVAL_UNITS_CRITICAL)
        if (usingMilitary("MILITARYAISTRATEGY_ENOUGH_NAVAL_UNITS")) naval = 0

        val wonderFlavorWeight = flavorWonder * rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_FLAVOR_WONDER)
        val weightOfWonders = (civ.ai.citySpecialization.nextWonderWeight * rules.defineFloat(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_NEXT_WONDER)).toInt()
        var wonder = minOf(wonderFlavorWeight, weightOfWonders).coerceAtLeast(0)
        if (criticalDefense) wonder /= 2

        var spaceship = 0
        if (canBuildSpaceshipParts(civ)) spaceship += flavorSpaceship * rules.define(AiDefine.AI_CITY_SPECIALIZATION_PRODUCTION_WEIGHT_FLAVOR_SPACESHIP)

        civ.ai.grandStrategyDef()?.let { gs ->
            val boost = gs.yields[Stat.Production.name] ?: 0
            if (boost > 0) {
                if (gs.flavorVector[Flavor.Offense.ordinal] > 0) militaryTraining += boost
                else if (gs.flavorVector[Flavor.Spaceship.ordinal] > 0) spaceship += boost
            }
        }
        return mutableListOf(
            Weighted(SUBTYPE_MILITARY_TRAINING, militaryTraining), Weighted(SUBTYPE_EMERGENCY_UNITS, emergencyUnits),
            Weighted(SUBTYPE_MILITARY_NAVAL, naval), Weighted(SUBTYPE_WONDER, wonder), Weighted(SUBTYPE_SPACESHIP, spaceship)
        )
    }

    /** CvCitySpecializationAI::CanBuildSpaceshipParts: the first city can train a spaceship part */
    private fun canBuildSpaceshipParts(civ: Civilization): Boolean {
        val city = civ.cities.firstOrNull() ?: return false
        return civ.gameInfo.ruleset.units.values.any {
            it.hasUnique(UniqueType.SpaceshipPart, GameContext.IgnoreConditionals) && it.isBuildable(city.cityConstructions)
        }
    }
    //endregion

    //region Selection and assignment

    private class Selection(val needed: MutableList<String>, var wonderChosen: Boolean)

    /** The production specialization of a subtype; null if the ruleset has none */
    private fun productionSpecialization(civ: Civilization, subtype: String): String? =
        rules(civ).citySpecializations.firstOrNull { yieldIndexOf(it) == PRODUCTION && it.subtype == subtype }?.name

    /**
     *  CvCitySpecializationAI::SelectSpecializations: as many specializations as cities that are not puppets, plus one. A wonder underway in the
     *  wonder city (not to be interrupted) takes the wonder specialization first. Then repeatedly the highest weighted yield: its first
     *  specialization, its weight reduced to weight × n / (n + 1) for the n-th pick; production picks a subtype ([selectProductionSpecialization]).
     */
    private fun selectSpecializations(civ: Civilization, weights: SpecializationWeights): Selection {
        val toAssign = civ.cities.count { !it.isPuppet } + 1
        val selection = Selection(ArrayList(), false)
        val numForYield = IntArray(5)
        val numForSubtype = HashMap<String, Int>()

        val wonderCity = wonderBuildCity(civ)
        val inProduction = wonderCity?.cityConstructions?.getCurrentConstruction() as? Building
        if (!civ.ai.citySpecialization.interruptWonders && inProduction != null && WonderProductionAi.isWonder(inProduction)) {
            wonderSpecialization(civ)?.let { selection.needed.add(it) }
            numForYield[PRODUCTION]++
            val production = weights.yields.first { it.key == PRODUCTION }
            val wonderSubtype = weights.subtypes.first { it.key == SUBTYPE_WONDER }
            production.weight -= wonderSubtype.weight
            wonderSubtype.weight = 0
            selection.wonderChosen = true
        } else civ.ai.wonderCityId = null

        while (selection.needed.size < toAssign) {
            weights.yields.sortByWeight()
            val top = weights.yields[0]
            val specialization: String?
            if (numSpecializationsForYield(civ, top.key) > 1) {
                // Only production has more than one specialization
                val (chosen, reduction) = selectProductionSpecialization(civ, weights, numForSubtype, selection)
                specialization = chosen
                numForYield[top.key]++
                top.weight -= reduction
            } else {
                specialization = firstSpecializationForYield(civ, top.key)
                numForYield[top.key]++
                top.weight = top.weight * numForYield[top.key] / (numForYield[top.key] + 1)
            }
            // A ruleset without the specialization: count the slot as general economic so the loop ends
            selection.needed.add(specialization ?: economicDefaultSpecialization(civ) ?: break)
        }
        return selection
    }

    /**
     *  CvCitySpecializationAI::SelectProductionSpecialization: the highest weighted subtype. The wonder subtype needs a wonder city
     *  ([findBestWonderCity], else the economic default instead) and is zeroed (reduction = its weight); others are reduced as yields are.
     *  @return the specialization and the reduction of the production weight
     */
    private fun selectProductionSpecialization(
        civ: Civilization, weights: SpecializationWeights, numForSubtype: HashMap<String, Int>, selection: Selection
    ): Pair<String?, Int> {
        weights.subtypes.sortByWeight()
        val top = weights.subtypes[0]
        var specialization = productionSpecialization(civ, top.key)
        val reduction: Int
        if (top.key == SUBTYPE_WONDER) {
            val city = findBestWonderCity(civ)
            if (city != null) {
                civ.ai.wonderCityId = city.id
                selection.wonderChosen = true
            } else specialization = economicDefaultSpecialization(civ)
            reduction = top.weight
            top.weight = 0
        } else {
            val count = (numForSubtype[top.key] ?: 0) + 1
            numForSubtype[top.key] = count
            val newWeight = top.weight * count / (count + 1)
            reduction = top.weight - newWeight
            top.weight = newWeight
        }
        return Pair(specialization, reduction)
    }

    /**
     *  CvCitySpecializationAI::FindBestWonderCity: among the cities that are not puppets and can build the next wonder desired, the highest
     *  production (×1.5 if its default specialization is the wonder one, then × (100 + its wonder production modifier) / 100); null if none
     *  has production above 0 or there is no next wonder.
     */
    fun findBestWonderCity(civ: Civilization): City? {
        val wonder = civ.ai.citySpecialization.nextWonderDesired?.let { civ.gameInfo.ruleset.buildings[it] } ?: return null
        val wonderSpecialization = wonderSpecialization(civ)
        var best: City? = null
        var bestProduction = 0
        for (city in civ.cities) {
            if (city.isPuppet || !wonder.isBuildable(city.cityConstructions)) continue
            var production = city.cityStats.currentCityStats.production.toInt()
            if (city.ai.defaultSpecialization == wonderSpecialization) production = production * 3 / 2
            production = production * (100 + wonderProductionModifier(city, wonder)) / 100
            if (production > bestProduction) {
                best = city
                bestProduction = production
            }
        }
        return best
    }

    /** CvCity::GetWonderProductionModifier: the city's percentage production bonuses towards [wonder] */
    private fun wonderProductionModifier(city: City, wonder: Building): Int =
        city.getMatchingUniques(UniqueType.PercentProductionWonders)
            .filter { wonder.matchesFilter(it.params[1], city.state) && city.matchesFilter(it.params[2]) }
            .sumOf { it.params[0].toInt() }

    private class CityData(val city: City, val weights: IntArray)

    /**
     *  CvCitySpecializationAI::AssignSpecializations: puppets get the economic default; the wonder city the wonder specialization; a city whose
     *  default or current specialization is needed keeps it; the others are rated per yield ([plotValueForYield] adjusted by
     *  [adjustValueBasedOnBuildings]). The next specialization desired (for the next city) is the yield a new city would improve most compared
     *  with the best site around ([findBestSites]), unless only one specialization is left or all left are general economic. The rest go to the
     *  best rated city each (general economic: sum of the ratings; coastal-only ones skip cities that are not coastal), or as the economic
     *  default to the first unassigned city.
     */
    private fun assignSpecializations(civ: Civilization, weights: SpecializationWeights) {
        val state = civ.ai.citySpecialization
        state.nextSpecializationDesired = null
        val wonderSpecialization = wonderSpecialization(civ)
        val economicDefault = economicDefaultSpecialization(civ)
        val selection = selectSpecializations(civ, weights)
        val needed = selection.needed
        val unassigned = ArrayList<CityData>()
        val tileYields = HashMap<Tile, Stats>()

        for (city in civ.cities) {
            if (city.isPuppet) {
                city.ai.changeSpecialization(economicDefault)
                continue
            }
            if (selection.wonderChosen && city.id == civ.ai.wonderCityId && needed.remove(wonderSpecialization)) {
                city.ai.changeSpecialization(wonderSpecialization)
                continue
            }
            val default = city.ai.defaultSpecialization
            if (default != null && needed.remove(default)) {
                city.ai.changeSpecialization(default)
                continue
            }
            val current = city.ai.specialization
            if (current != null && needed.remove(current)) {
                city.ai.changeSpecialization(current)
                continue
            }
            val values = IntArray(4) { yieldIndex ->
                val raw = plotValueForYield(civ, city.getCenterTile(), yieldIndex, tileYields)
                adjustValueBasedOnBuildings(city, yieldIndex, raw).coerceAtLeast(0)
            }
            unassigned.add(CityData(city, values))
        }

        if (unassigned.isEmpty()) {
            state.nextSpecializationDesired = needed.firstOrNull()
            return
        }
        if (needed.all { it == economicDefault }) {
            state.nextSpecializationDesired = economicDefault
            if (needed.isNotEmpty()) needed.removeAt(0)
        } else {
            val bestSites = findBestSites(civ, tileYields)
            val improvements = MutableList(4) { yieldIndex ->
                val bestDelta = unassigned.maxOf { it.weights[yieldIndex] - bestSites[yieldIndex] }
                Weighted(yieldIndex, if (bestDelta > 0) 0 else -bestDelta)
            }
            improvements.sortByWeight()
            for (improvement in improvements) {
                val match = needed.firstOrNull { specializationDef(civ, it)?.let { def -> yieldIndexOf(def) } == improvement.key } ?: continue
                state.nextSpecializationDesired = match
                needed.remove(match)
                break
            }
        }

        for (specialization in needed) {
            if (unassigned.isEmpty()) break
            val def = specializationDef(civ, specialization) ?: continue
            val yieldIndex = yieldIndexOf(def)
            var best: CityData? = null
            var bestValue = -1
            for (data in unassigned) {
                if (def.mustBeCoastal && !data.city.isCoastal()) continue
                val value = if (yieldIndex == GENERAL) data.weights.sum() else data.weights[yieldIndex]
                if (value > bestValue) {
                    bestValue = value
                    best = data
                }
            }
            if (best != null) {
                best.city.ai.changeSpecialization(specialization)
                unassigned.remove(best)
            } else {
                unassigned.removeAt(0).city.ai.changeSpecialization(economicDefault)
            }
        }
    }

    /** CvPlot::getYield, cached for one evaluation */
    private fun tileYield(civ: Civilization, tile: Tile, stat: Stat, cache: HashMap<Tile, Stats>): Int =
        cache.getOrPut(tile) { tile.stats.getTileStats(civ) }[stat].toInt()

    /**
     *  The tiles PlotValueForSpecificYield and PlotValueForScience look at: the plot indexes 1 until AI_CITY_SPECIALIZATION_YIELD_NUM_TILES_CONSIDERED
     *  of plotCity - the first ring, then the second ring in its order, so with the XML's 18 the last tile of the second ring and the third ring
     *  are never looked at (Unciv's ring order stands in for the original's plot order).
     */
    private fun consideredTiles(center: Tile, count: Int): Sequence<Tile> =
        (center.neighbors + center.getTilesAtDistance(2) + center.getTilesAtDistance(3)).take(count - 1)

    private fun ringMultiplier(rules: AiRuleset, distance: Int): Int = when (distance) {
        1 -> rules.define(AiDefine.AI_CITY_SPECIALIZATION_YIELD_WEIGHT_FIRST_RING)
        2 -> rules.define(AiDefine.AI_CITY_SPECIALIZATION_YIELD_WEIGHT_SECOND_RING)
        3 -> rules.define(AiDefine.AI_CITY_SPECIALIZATION_YIELD_WEIGHT_THIRD_RING)
        else -> 0
    }

    /**
     *  CvCitySpecializationAI::PlotValueForSpecificYield (food, production, gold): Σ tile yield × ring weight (8/5/2) over the considered tiles,
     *  tiles another player owns counting 0. Science (PlotValueForScience): Σ (food + science) × ring weight, plus the ring weight of each
     *  clear tile (no resource, feature, hill or improvement).
     */
    fun plotValueForYield(civ: Civilization, center: Tile, yieldIndex: Int, cache: HashMap<Tile, Stats> = HashMap()): Int {
        val rules = rules(civ)
        var total = 0
        for (tile in consideredTiles(center, rules.define(AiDefine.AI_CITY_SPECIALIZATION_YIELD_NUM_TILES_CONSIDERED))) {
            val owner = tile.getOwner()
            val multiplier = if (owner != null && owner != civ) 0 else ringMultiplier(rules, tile.aerialDistanceTo(center))
            if (yieldIndex == SCIENCE) {
                total += (tileYield(civ, tile, Stat.Food, cache) + tileYield(civ, tile, Stat.Science, cache)) * multiplier
                if (tile.resource == null && tile.terrainFeatures.isEmpty() && !tile.isHill() && tile.improvement == null) total += multiplier
            } else total += tileYield(civ, tile, yieldStats[yieldIndex], cache) * multiplier
        }
        return total
    }

    /**
     *  CvCitySpecializationAI::AdjustValueBasedOnBuildings: × (100 + yield modifier) / 100; × (100 + yield per citizen ×100) / 100 if any, and then
     *  (the original tests the per-citizen yield here) × (100 + yield per religion ×100 × religions with followers) / 100; × (100 + 20 × building
     *  yield) / 100 if any; food × 100 / (100 - food kept %, at most 99) in integers; production doubled with a land military training building.
     */
    fun adjustValueBasedOnBuildings(city: City, yieldIndex: Int, initialValue: Int): Int {
        val stat = yieldStats[yieldIndex]
        var value = initialValue * (100 + CityStrategyAi.buildingYieldModifier(city, stat).toInt()) / 100
        val perPop = CityStrategyAi.yieldPerPopTimes100(city, stat)
        if (perPop > 0) {
            value = value * (100 + perPop) / 100
            val perReligion = yieldPerReligionTimes100(city, stat)
            value = value * (100 + perReligion * city.religion.getNumberOfFollowers().size) / 100
        }
        val buildingYield = CityStrategyAi.baseYieldFromBuildings(city, stat)
        if (buildingYield > 0) value = value * (100 + buildingYield * 20) / 100
        when (stat) {
            Stat.Food -> value *= 100 / (100 - maxFoodKeptPercent(city).coerceAtMost(99))
            Stat.Production -> if (CityStrategyAi.landFreeExperience(city) > 0) value *= 2
            else -> {}
        }
        return value
    }

    /** CvCity::GetYieldPerReligionTimes100: what the city's buildings give per religion with followers, ×100 */
    private fun yieldPerReligionTimes100(city: City, stat: Stat): Int {
        var total = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.StatsPerReligionWithFollowers, city.state))
                total += (unique.stats[stat] * 100).toInt()
        return total
    }

    /** CvCity::getMaxFoodKeptPercent */
    private fun maxFoodKeptPercent(city: City): Int =
        city.getMatchingUniques(UniqueType.CarryOverFood).filter { city.matchesFilter(it.params[1]) }.sumOf { it.params[0].toInt() }

    /**
     *  CvCitySpecializationAI::FindBestSites: the best [plotValueForYield] per yield over the revealed plots a city can be founded on
     *  (CvCitySiteEvaluator::CanFound) within SETTLER_EVALUATION_DISTANCE / 2 of one of our cities on the same landmass.
     */
    private fun findBestSites(civ: Civilization, cache: HashMap<Tile, Stats>): IntArray {
        val best = IntArray(4)
        val distance = rules(civ).define(AiDefine.SETTLER_EVALUATION_DISTANCE) / 2
        val evaluator = CitySiteEvaluator(civ)
        val visited = HashSet<Tile>()
        for (city in civ.cities) {
            val continent = city.getCenterTile().getContinent()
            city.getCenterTile().forEachTileInDistance(distance) { tile ->
                if (tile.getContinent() != continent || !visited.add(tile)) return@forEachTileInDistance
                if (!tile.isExplored(civ) || !evaluator.canFound(tile)) return@forEachTileInDistance
                for (yieldIndex in 0 until 4) {
                    val value = plotValueForYield(civ, tile, yieldIndex, cache)
                    if (value > best[yieldIndex]) best[yieldIndex] = value
                }
            }
        }
        return best
    }
    //endregion
}
