package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.pow

/** A wonder chosen by [WonderProductionAi.chooseWonderForGreatEngineer] and the city to build it in (null if no city can) */
class EngineerWonder(val wonder: Building, val totalWeight: Int, val city: City?)

/**
 *  CvWonderProductionAI (CvWonderProductionAI.cpp): which wonder a player wants next.
 *
 *  Weights (CvWonderProductionAI::m_WonderAIWeights, [CivAi.wonderWeights]): CvWonderProductionAI::FlavorUpdate adds, for each wonder, Σ over
 *  flavors of the recipient's latest flavor (negative read as 0, GetLatestFlavorValue) × the wonder's Building_Flavors, and nothing ever
 *  resets them but CvWonderProductionAI::Init (the city production AIs reset in CvCityStrategyAI::FlavorUpdate; this one does not). It runs on
 *  every broadcast to the player-level recipients: when the flavor recipient is registered and when the personality is broadcast again
 *  (CvFlavorManager::Init, AdjustWeightsForMap), and on each economic or military strategy adopted or dropped
 *  (CvFlavorManager::ChangeFlavors). So the weights are the flavors summed once per broadcast, see [flavorUpdate].
 *  Deviation: the intermediate flavors of a broadcast are [CivAi.flavors] (re-derived from the active strategies, each delta clamped in
 *  definition order rather than adoption order, which only differs at FLAVOR_MIN_VALUE / FLAVOR_MAX_VALUE).
 *
 *  The wonder city is CvCitySpecializationAI::GetWonderBuildCity, stored as [CivAi.wonderCityId] by the city specialization AI.
 */
object WonderProductionAi {

    /** CvWonderProductionAI::IsWonder: world, team or national wonder */
    fun isWonder(building: Building): Boolean = building.isWonder || building.isNationalWonder

    /** CvCitySpecializationAI::GetWonderBuildCity: the city chosen to build wonders, null if none */
    fun wonderBuildCity(civ: Civilization): City? {
        val id = civ.ai.wonderCityId ?: return null
        return civ.cities.firstOrNull { it.id == id }
    }

    /** CvWonderProductionAI::Init -> Reset: all weights 0 */
    fun reset(civ: Civilization) = civ.ai.wonderWeights.clear()

    /**
     *  CvWonderProductionAI::FlavorUpdate / AddFlavorWeights: adds Σ max(0, flavor) × the wonder's flavor to every wonder's weight. [flavors] is the
     *  recipient's latest flavor vector by [Flavor.ordinal].
     */
    fun flavorUpdate(civ: Civilization, flavors: IntArray = civ.ai.flavors()) {
        val ai = civ.gameInfo.ruleset.ai
        val weights = civ.ai.wonderWeights
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (!isWonder(building)) continue
            val wonderFlavors = ai.flavorsOf(building)
            var sum = 0
            for (i in 0 until Flavor.count) sum += max(0, flavors[i]) * wonderFlavors[i]
            weights[building.name] = (weights[building.name] ?: 0) + sum
        }
    }

    /** Saves from before the weights were kept have none: they start at the current flavors added once */
    internal fun ensureWeights(civ: Civilization) {
        if (civ.ai.wonderWeights.isEmpty()) flavorUpdate(civ)
    }

    /** CvWonderProductionAI::GetWeight: the accumulated weight of [wonder] */
    fun weight(civ: Civilization, wonder: Building): Int {
        ensureWeights(civ)
        return civ.ai.wonderWeights[wonder.name] ?: 0
    }

    /**
     *  CvWonderProductionAI::ChooseWonder: among the wonders some city can build, weights by [weight] (×25 if the wonder city has production
     *  in it already; 0 for a diplomatic-voting wonder unless [isWorthBuildingUnitedNations]), reweighted by the turns the wonder city needs
     *  ([reweightByTurnsLeft]), divided by 1 + the majors building it if [adjustForOtherPlayers] (world wonders only); then a weighted roll among
     *  the handicap's CityProductionNumOptionsConsidered best.
     *  @return the chosen wonder (null if none has weight) and the total weight of all candidates
     */
    fun chooseWonder(civ: Civilization, adjustForOtherPlayers: Boolean): Pair<Building?, Int> {
        val wonderCity = wonderBuildCity(civ) ?: civ.cities.firstOrNull() ?: return Pair(null, 0)
        val productionPerTurn = max(1, wonderCity.cityStats.currentCityStats.production.toInt())
        val candidates = ArrayList<Pair<Building, Int>>()
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (!isWonder(building) || !haveCityToBuild(civ, building)) continue
            val turnsRequired = max(1, building.getProductionCost(civ, wonderCity) / productionPerTurn)
            val alreadyStarted = wonderCity.cityConstructions.getWorkDone(building.name) > 0
            var weight = weight(civ, building)
            if (alreadyStarted) weight *= 25
            if (isDiplomaticVoting(building) && !isWorthBuildingUnitedNations(civ)) weight = 0
            weight = reweightByTurnsLeft(civ, weight, turnsRequired)
            if (adjustForOtherPlayers && building.isWonder) {
                val othersConstructing = civ.gameInfo.civilizations.count { major ->
                    major.isMajorCiv && major.cities.any { it.cityConstructions.isBeingConstructedOrEnqueued(building.name) }
                }
                weight /= 1 + othersConstructing
            }
            candidates.add(building to weight)
        }
        if (candidates.isEmpty()) return Pair(null, 0)
        candidates.sortByDescending { it.second }
        val totalWeight = candidates.sumOf { it.second }
        if (totalWeight <= 0) return Pair(null, 0)
        return Pair(chooseFromTopChoices(civ, candidates, cityProductionNumOptions(civ), "ChooseWonder"), totalWeight)
    }

    /**
     *  CvWonderProductionAI::ChooseWonderForGreatEngineer: the wonder with the highest raw [weight] (diplomatic voting zeroed as in [chooseWonder]),
     *  built in the wonder city if it can, else in the first city that can. Null if no wonder some city can build has any weight.
     */
    fun chooseWonderForGreatEngineer(civ: Civilization): EngineerWonder? {
        val wonderCity = wonderBuildCity(civ) ?: civ.cities.firstOrNull() ?: return null
        var best: Building? = null
        var bestWeight = 0
        var totalWeight = 0
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (!isWonder(building) || !haveCityToBuild(civ, building)) continue
            var weight = weight(civ, building)
            if (isDiplomaticVoting(building) && !isWorthBuildingUnitedNations(civ)) weight = 0
            totalWeight += weight
            if (weight > bestWeight) {
                bestWeight = weight
                best = building
            }
        }
        if (best == null || totalWeight <= 0) return null
        val city = if (canConstruct(wonderCity, best)) wonderCity else civ.cities.firstOrNull { canConstruct(it, best) }
        return EngineerWonder(best, totalWeight, city)
    }

    /**
     *  CityStrategyAIHelpers::ReweightByTurnsLeft: weight / turns ^ (AI_PRODUCTION_WEIGHT_BASE_MOD + AI_PRODUCTION_WEIGHT_MOD_PER_TURN_LEFT × turns)
     */
    fun reweightByTurnsLeft(civ: Civilization, weight: Int, turnsLeft: Int): Int {
        val rules = civ.gameInfo.ruleset.ai
        val exponent = rules.defineFloat(AiDefine.AI_PRODUCTION_WEIGHT_BASE_MOD).toDouble() +
            rules.defineFloat(AiDefine.AI_PRODUCTION_WEIGHT_MOD_PER_TURN_LEFT).toDouble() * turnsLeft
        return (weight.toDouble() / turnsLeft.toDouble().pow(exponent)).toInt()
    }

    /** HandicapInfos.CityProductionNumOptionsConsidered of the game's difficulty (2 if the ruleset has no entry) */
    fun cityProductionNumOptions(civ: Civilization): Int =
        civ.gameInfo.ruleset.ai.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get("CityProductionNumOptionsConsidered") ?: 2

    /** CvWeightedVector::ChooseFromTopChoices on [sorted] (best first): a roll below the summed weight of the first [numChoices] */
    internal fun <T> chooseFromTopChoices(civ: Civilization, sorted: List<Pair<T, Int>>, numChoices: Int, purpose: String): T {
        val count = numChoices.coerceIn(1, sorted.size)
        var total = 0
        for (i in 0 until count) total += sorted[i].second
        if (total <= 0) return sorted[0].first
        var roll = civ.ai.random(purpose).nextInt(total)
        for (i in 0 until count) {
            roll -= sorted[i].second
            if (roll < 0) return sorted[i].first
        }
        return sorted[count - 1].first
    }

    /** CvWonderProductionAI::HaveCityToBuild */
    private fun haveCityToBuild(civ: Civilization, building: Building) = civ.cities.any { canConstruct(it, building) }

    /** CvCity::canConstruct */
    private fun canConstruct(city: City, building: Building) = building.isBuildable(city.cityConstructions)

    /** CvBuildingEntry::IsDiplomaticVoting: the United Nations of the rulesets that have it as a wonder */
    internal fun isDiplomaticVoting(building: Building) = building.hasUnique(UniqueType.OneTimeTriggerVoting, GameContext.IgnoreConditionals)

    /**
     *  The diplomatic-voting test of ChooseWonder: going for a diplomatic victory and gold ≥ 500 × the votes still to buy, where the secured
     *  votes are the city-states allied to us. Deviation: the original also counts players we liberated; Unciv does not record who revived a civ.
     */
    internal fun isWorthBuildingUnitedNations(civ: Civilization): Boolean {
        if (!civ.ai.isGoingForDiploVictory()) return false
        val securedVotes = civ.gameInfo.civilizations.count { it.isCityState && it.isAlive() && it.allyCiv == civ }
        val toBuyOff = max(0, civ.victoryManager.votesNeededForDiplomaticVictory() - securedVotes)
        return civ.gold >= toBuyOff * 500
    }
}
