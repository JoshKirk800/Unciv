package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.DiplomatUsefulness
import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.civilization.MinorCivHelpers
import com.unciv.logic.automation.civilization.TechAutomation
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MinorCivApproach
import kotlin.math.ceil
import kotlin.math.floor

/** How many living spies the AI wants in each role (CvEspionageAI::FindTargetSpyNumbers) */
class SpyTargets(var offensive: Int = 0, var defensive: Int = 0, var cityState: Int = 0, var diplomat: Int = 0)

/**
 * The spy AI of a civilization: CvEspionageAI (CvEspionageClasses.cpp), run once per turn by [automateSpies] (CvEspionageAI::DoTurn).
 *
 * The thefts themselves happen in [Spy.endTurn] (the AI takes the first stealable technology, CvEspionageAI::StealTechnology).
 * Inputs the original reads and what stands in for them:
 * - Declaration of Friendship and denunciation: [OpinionAi.isDoFAccepted], [OpinionAi.isDenouncedPlayer]; stop-spying promise: the
 *   target's [DiplomacyFlags.AgreedToNotSendSpies] towards us; research agreement [DiplomacyFlags.ResearchAgreement], defensive pact
 *   [DiplomaticStatus.DefensivePact], open borders the two `hasOpenBorders`; tech rank [TechAutomation.techRatio].
 * - War goal: `civ.ai.diplomacy.warGoal`; minor civ approach: `civ.ai.minorCivApproaches`; diplomat usefulness: CvLeagueAI's port in
 *   [LeagueAutomation]; city-state rules [MinorCivHelpers]; influence levels: [com.unciv.logic.civilization.managers.CultureManager.getInfluenceLevel] (unmet = no level).
 * - The steal-rate memory of the original's city espionage (m_aiLastBasePotential, m_aiNumTimesCityRobbed) and the AI memory
 *   (m_aiCivOutOfTechTurn, m_bUNCountdownStarted) are in [com.unciv.logic.civilization.managers.EspionageManager].
 *
 * Kept from the shipped DLL: when a diplomat is placed the defensive counter is the one raised (CvEspionageAI::DoTurn), and a placement into a
 * city the spy cannot enter still uses up the role's turn. Deviation: scores are 64-bit, so no overflow of "maximum turns x modifier".
 */
class EspionageAutomation(private val civInfo: Civilization) {
    private val espionage = civInfo.espionageManager
    private val gameInfo = civInfo.gameInfo

    /** CvEspionageAI::DoTurn */
    fun automateSpies() = timeThis("AI.Espionage") {
        if (!civInfo.isMajorCiv) return@timeThis
        val spies = espionage.spyList
        updateCivOutOfTechTurn()
        attemptCoups()

        // EvaluateUnassignedSpies, EvaluateDefensiveSpies, EvaluateDiplomatSpies, and the flags CvPlayerEspionage::ProcessSpy sets every turn
        for (spy in spies) if (spy.isAlive() && isReconsideredEveryTurn(spy)) spy.evaluateReassignment = true

        // When the United Nations countdown starts, every spy reconsiders its post
        if (!espionage.unCountdownStarted && gameInfo.league?.unitedNations == true) {
            espionage.unCountdownStarted = true
            for (spy in spies) if (spy.isAlive()) spy.evaluateReassignment = true
        }

        val targets = findTargetSpyNumbers()
        val diplomatCities = if (targets.diplomat > 0) buildDiplomatCityList() else emptyList()
        val offenseCities = if (targets.offensive > 0) buildOffenseCityList() else emptyList()
        val defenseCities = if (targets.defensive > 0) buildDefenseCityList() else emptyList()
        val minorCities = if (targets.cityState > 0) buildMinorCityList() else emptyList()

        var assignedDiplomats = 0
        var assignedDefense = 0
        var assignedOffense = 0
        var assignedCityState = 0
        // Spies in a city of the top of the list of their role stay; flagged spies elsewhere are pulled out
        for (spy in spies) {
            if (!spy.isAlive()) continue
            val city = spy.getCityOrNull() ?: continue
            val alreadyAssigned = when {
                spy.isDiplomat && city in diplomatCities.take(targets.diplomat) -> { assignedDiplomats++; true }
                city in defenseCities.take(targets.defensive) -> { assignedDefense++; true }
                !spy.isDiplomat && city in offenseCities.take(targets.offensive) -> { assignedOffense++; true }
                city in minorCities.take(targets.cityState) -> { assignedCityState++; true }
                else -> false
            }
            if (spy.evaluateReassignment) {
                if (alreadyAssigned) spy.evaluateReassignment = false
                else spy.moveTo(null)
            }
        }

        // Flagged spies in the hideout go where a role still needs one: diplomat, defense, offense, city-state
        for (spy in spies) {
            if (!spy.isAlive() || !spy.evaluateReassignment) continue
            spy.evaluateReassignment = false
            if (spy.getCityOrNull() != null) continue
            when {
                targets.diplomat > assignedDiplomats -> {
                    // The shipped DLL raises the defensive counter here, not the diplomat one
                    if (placeInFirstFree(spy, diplomatCities, targets.diplomat, asDiplomat = true)) assignedDefense++
                }
                targets.defensive > assignedDefense -> if (placeInFirstFree(spy, defenseCities, targets.defensive)) assignedDefense++
                targets.offensive > assignedOffense -> if (placeInFirstFree(spy, offenseCities, targets.offensive)) assignedOffense++
                targets.cityState > assignedCityState -> if (placeInFirstFree(spy, minorCities, targets.cityState)) assignedCityState++
            }
        }
    }

    /**
     * The first of the top [count] cities of [cities] without one of our spies gets [spy] (CvPlayerEspionage::MoveSpyTo, which does nothing
     * if CanMoveSpyTo fails). @return whether such a city was found, whether or not the spy could enter it
     */
    private fun placeInFirstFree(spy: Spy, cities: List<City>, count: Int, asDiplomat: Boolean = false): Boolean {
        for (city in cities.take(count)) {
            if (espionage.getSpyAssignedToCity(city) != null) continue
            if ((city.civ == civInfo || civInfo.knows(city.civ)) && spy.canMoveTo(city, asDiplomat)) spy.moveTo(city, asDiplomat)
            return true
        }
        return false
    }

    /**
     * Spies the original flags for reassignment every turn: in the hideout, in our own cities, diplomats, and spies in a major civilization's
     * city that have nothing left to steal or steal less than 1 science per turn (CvPlayerEspionage::ProcessSpy).
     */
    private fun isReconsideredEveryTurn(spy: Spy): Boolean {
        val city = spy.getCityOrNull() ?: return true
        if (city.civ == civInfo || spy.isDiplomat) return true
        if (!city.civ.isMajorCiv) return false
        return when (spy.action) {
            SpyAction.Surveillance -> true // Unciv's state for "nothing to steal" or "no science here"
            SpyAction.StealingTech -> spy.getStealProgressPerTurn() < 1f
            else -> false
        }
    }

    //region Thefts and coups
    /** CvEspionageAI::UpdateCivOutOfTechTurn: remembers the turn each civilization we watch had nothing left to steal */
    private fun updateCivOutOfTechTurn() {
        for (other in gameInfo.civilizations) {
            if (other == civInfo || !other.isMajorCiv) continue
            val watched = espionage.spyList.any { spy -> spy.isSetUp() && spy.getCityOrNull()?.civ == other }
            if (!watched) continue
            if (espionage.getTechsToSteal(other).isEmpty()) espionage.civOutOfTechTurn[other.civID] = gameInfo.turns
        }
    }

    /**
     * CvEspionageAI::AttemptCoups: every [isCoupCandidate] spy rolls 0..99 and stages the coup when the roll is below its chance of success
     * in percent ([Spy.getCoupChanceOfSuccess], hidden defenders included).
     */
    private fun attemptCoups() {
        val random = civInfo.ai.random("AttemptCoups")
        for (spy in espionage.spyList) {
            if (!isCoupCandidate(spy)) continue
            val chancePercent = (spy.getCoupChanceOfSuccess(true) * 100).toInt()
            if (random.nextInt(100) < chancePercent) {
                spy.setAction(SpyAction.Coup, 1)
                spy.evaluateReassignment = true // CvPlayerEspionage::AttemptCoup
            }
        }
    }

    /**
     * Whether [spy] considers a coup in CvEspionageAI::AttemptCoups: CvPlayerEspionage::CanStageCoup (surveillance set up in a city-state
     * whose ally is another civilization), and that ally is not one we have a Declaration of Friendship with.
     */
    fun isCoupCandidate(spy: Spy): Boolean {
        if (!spy.isAlive() || !spy.isSetUp() || spy.action == SpyAction.Coup) return false
        val owner = spy.getCityOrNull()?.civ ?: return false
        val ally = owner.allyCiv ?: return false
        return owner.isCityState && ally != civInfo && !OpinionAi.isDoFAccepted(civInfo, ally)
    }
    //endregion

    //region Spy numbers
    /** CvEspionageAI::FindTargetSpyNumbers: splits the living spies by role from the grand strategy and our rank in technologies */
    fun findTargetSpyNumbers(): SpyTargets {
        val targets = SpyTargets()
        var remaining = espionage.spyList.count { it.isAlive() }
        if (remaining == 0) return targets
        val ai = civInfo.ai
        val techRatio = TechAutomation.techRatio(civInfo)
        var offenseAllocated = false

        when {
            ai.isGoingForDiploVictory() -> {
                var diplomats = 0f
                for (other in gameInfo.civilizations) {
                    if (other == civInfo || !other.isMajorCiv || !other.isAlive()) continue
                    diplomats += when (LeagueAutomation.getDiplomatUsefulnessAtCiv(civInfo, other)) {
                        DiplomatUsefulness.HIGH -> 1f
                        DiplomatUsefulness.MEDIUM -> 0.5f
                        DiplomatUsefulness.LOW -> 0.25f
                        DiplomatUsefulness.NONE -> 0f
                    }
                }
                targets.diplomat = minOf(remaining, floor(diplomats).toInt())
                remaining -= targets.diplomat
                targets.offensive = ceil(techRatio * remaining).toInt()
                offenseAllocated = true
                remaining -= targets.offensive
                targets.cityState = minOf(remaining, metCityStatesAtPeace().count())
            }
            ai.isGoingForWorldConquest() -> {
                targets.offensive = ceil(techRatio * remaining).toInt()
                offenseAllocated = true
                remaining -= targets.offensive
                targets.cityState = minOf(remaining, metCityStatesAtPeace().count { it.cityStateType.name == MILITARISTIC })
            }
            ai.isGoingForCultureVictory() -> {
                targets.diplomat = minOf(remaining, 2, getMaxPropagandaDiplomatsWanted())
                remaining -= targets.diplomat
            }
            ai.isGoingForSpaceshipVictory() -> targets.cityState = 0
            else -> targets.cityState = remaining / 3
        }

        remaining -= targets.cityState
        if (!offenseAllocated) {
            targets.offensive = ceil(techRatio * remaining).toInt()
            remaining -= targets.offensive
        }
        targets.defensive = remaining

        // Rationalization: no more defenders than own cities, no more thieves than foreign cities we know of
        val foreignCities = gameInfo.civilizations.asSequence()
            .filter { it != civInfo && it.isMajorCiv }
            .sumOf { other -> other.cities.count { it.getCenterTile().isExplored(civInfo) } }
        val ownCities = civInfo.cities.size
        while (targets.defensive > ownCities) {
            if (targets.offensive < foreignCities) targets.offensive++
            targets.defensive--
        }
        while (targets.offensive > foreignCities) {
            if (targets.defensive < ownCities) targets.defensive++
            targets.offensive--
        }
        return targets
    }

    private fun metCityStatesAtPeace(): Sequence<Civilization> = gameInfo.civilizations.asSequence()
        .filter { it.isCityState && it.isAlive() && civInfo.knows(it) && !civInfo.isAtWarWith(it) }

    /** CvPlayerCulture::WantsDiplomatDoingPropaganda: once every other civilization is at Exotic influence or above, the two highest levels */
    fun wantsDiplomatDoingPropaganda(target: Civilization): Boolean = propagandaTargets()?.contains(target) == true

    /** CvPlayerCulture::GetMaxPropagandaDiplomatsWanted */
    private fun getMaxPropagandaDiplomatsWanted(): Int = propagandaTargets()?.size ?: 0

    /** The (at most two) civilizations a propaganda diplomat is wanted with, `null` if some civilization is below Exotic influence */
    private fun propagandaTargets(): List<Civilization>? {
        var first: Civilization? = null
        var firstLevel = -1
        var second: Civilization? = null
        var secondLevel = -1
        for (other in gameInfo.civilizations) {
            if (other == civInfo || !other.isMajorCiv || !other.isAlive()) continue
            val level = civInfo.culture.getInfluenceLevel(other)?.ordinal ?: InfluenceLevel.Unknown.ordinal
            if (level < InfluenceLevel.Exotic.ordinal) return null
            if (level > firstLevel && level > secondLevel) {
                second = first; secondLevel = firstLevel
                first = other; firstLevel = level
            } else if (level > secondLevel) {
                second = other; secondLevel = level
            }
        }
        return listOfNotNull(first, second)
    }
    //endregion

    //region City lists
    /**
     * CvEspionageAI::BuildDiplomatCityList: going for Culture, the capitals of the civilizations [wantsDiplomatDoingPropaganda];
     * otherwise the revealed capitals ranked by diplomat usefulness (none excluded).
     */
    fun buildDiplomatCityList(): List<City> {
        val majors = gameInfo.civilizations.filter { it != civInfo && it.isMajorCiv && it.isAlive() }
        if (civInfo.ai.isGoingForCultureVictory()) {
            val wanted = propagandaTargets() ?: return emptyList()
            return majors.filter { it in wanted }.mapNotNull { it.getCapital() }
        }
        return majors.mapNotNull { other ->
            val usefulness = LeagueAutomation.getDiplomatUsefulnessAtCiv(civInfo, other)
            if (usefulness == DiplomatUsefulness.NONE) return@mapNotNull null
            val capital = other.getCapital() ?: return@mapNotNull null
            if (!capital.getCenterTile().isExplored(civInfo)) return@mapNotNull null
            capital to usefulness.ordinal
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** CvEspionageAI::BuildOffenseCityList: the revealed cities of the other major civilizations, by [offenseScore], lowest first */
    fun buildOffenseCityList(): List<City> {
        val maxTechCost = maxResearchableTechCostTimes100()
        val maxStealCost = HashMap<Civilization, Int>()
        val scored = ArrayList<Pair<City, Long>>()
        for (other in gameInfo.civilizations) {
            if (other == civInfo || !other.isMajorCiv) continue
            for (city in other.cities) {
                if (!city.getCenterTile().isExplored(civInfo)) continue
                val cost = maxStealCost.getOrPut(other) { maxStealableTechCostTimes100(other) }.takeIf { it > 0 } ?: maxTechCost
                scored.add(city to offenseScore(city, cost))
            }
        }
        return scored.sortedBy { it.second }.map { it.first }
    }

    /**
     * The score of a city in CvEspionageAI::BuildOffenseCityList: estimated turns to steal x diplomatic multiplier; a civilization that ran out
     * of stealable technologies this turn gets the maximum, one that did before is multiplied by that turn.
     * @param techCostTimes100 the most expensive technology we could steal there (else that we could research), x100
     */
    fun offenseScore(city: City, techCostTimes100: Int): Long {
        val target = city.civ
        var score = estimatedTurnsToSteal(city, techCostTimes100) * diplomaticModifier(target)
        val outOfTechTurn = espionage.civOutOfTechTurn[target.civID] ?: -1
        if (outOfTechTurn == gameInfo.turns) score = MAX_INT
        else if (outOfTechTurn > 0) score *= outOfTechTurn
        return score
    }

    /** CvEspionageAI::BuildOffenseCityList: the live rate if our spy steals there, else the potential seen last time, else the population */
    private fun estimatedTurnsToSteal(city: City, techCostTimes100: Int): Long {
        val spy = espionage.getSpyAssignedToCity(city)
        if (spy != null && spy.action == SpyAction.StealingTech) {
            return if (spy.getStealProgressPerTurn() > 0f) spy.turnsRemainingForAction.toLong() else MAX_INT
        }
        val lastPotential = espionage.lastBasePotential[city.id] ?: 0
        if (lastPotential > 0) return (techCostTimes100 / lastPotential).toLong()
        val population = city.population.population
        return if (population > 0) (techCostTimes100 / (population * 100)).toLong() else MAX_INT
    }

    /** The diplomatic multiplier of CvEspionageAI::BuildOffenseCityList: 1 at war or preparing one, otherwise 10 and more */
    fun diplomaticModifier(target: Civilization): Long {
        if (civInfo.ai.diplomacy.warGoal(target) == WarGoal.Prepare || civInfo.isAtWarWith(target)) return 1
        var modifier = 10L
        val ours = civInfo.getDiplomacyManager(target) ?: return modifier
        val theirs = target.getDiplomacyManager(civInfo)
        if (theirs?.hasFlag(DiplomacyFlags.AgreedToNotSendSpies) == true) modifier *= 100
        if (OpinionAi.isDenouncedPlayer(civInfo, target) || OpinionAi.isDenouncedPlayer(target, civInfo)) modifier /= 2
        else if (OpinionAi.isDoFAccepted(civInfo, target)) modifier *= 50
        if (ours.hasFlag(DiplomacyFlags.ResearchAgreement)) modifier *= 5
        if (ours.diplomaticStatus == DiplomaticStatus.DefensivePact) modifier *= 50
        if (theirs?.hasOpenBorders == true) modifier *= 2 // we let them in
        if (ours.hasOpenBorders) modifier *= 2 // they let us in
        return modifier
    }

    private fun maxResearchableTechCostTimes100(): Int = gameInfo.ruleset.technologies.keys.asSequence()
        .filter { !civInfo.tech.isResearched(it) && civInfo.tech.canBeResearched(it) }
        .maxOfOrNull { civInfo.tech.costOfTech(it) * 100 } ?: -1

    /** CvPlayerEspionage::BuildStealableTechList: m_aiMaxTechCost */
    private fun maxStealableTechCostTimes100(other: Civilization): Int =
        espionage.getTechsToSteal(other).maxOfOrNull { civInfo.tech.costOfTech(it) * 100 } ?: -1

    /** CvEspionageAI::BuildDefenseCityList: our cities by how fast a spy would steal there, fastest first */
    fun buildDefenseCityList(): List<City> = civInfo.cities.sortedByDescending { espionage.getBaseStealRateTimes100(it) }

    /** CvEspionageAI::BuildMinorCityList: one city per met city-state at peace with us, by [minorCityScore], highest first */
    fun buildMinorCityList(): List<City> {
        val plan = getCityStatePlan()
        val growthFlavor = civInfo.ai.personalityAndGrandStrategy(Flavor.Growth)
        val scored = ArrayList<Pair<City, Long>>()
        for (cityState in gameInfo.civilizations) {
            if (!cityState.isCityState || !cityState.isAlive() || !civInfo.knows(cityState) || civInfo.isAtWarWith(cityState)) continue
            val city = cityState.cities.firstOrNull { it.getCenterTile().isExplored(civInfo) } ?: continue
            scored.add(city to minorCityScore(cityState, plan, growthFlavor))
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** The score of a city-state in CvEspionageAI::BuildMinorCityList */
    fun minorCityScore(cityState: Civilization, plan: CityStatePlan, growthFlavor: Int): Long {
        val rules = gameInfo.ruleset.ai
        val influence = MinorCivHelpers.influence(cityState, civInfo)
        val isAlly = cityState.allyCiv == civInfo
        val ai = civInfo.ai
        var value = 100L
        when (plan.kind) {
            CityStatePlanKind.PlayNormal -> {
                val approach = ai.minorCivApproaches[cityState.civID] ?: MinorCivApproach.Ignore
                if (approach == MinorCivApproach.Protective || approach == MinorCivApproach.Friendly) {
                    val trait = cityState.cityStateType.name
                    if (trait == MARITIME && civInfo.getHappiness() >= 0)
                        value += rules.define(AiDefine.MC_GIFT_WEIGHT_MARITIME_GROWTH).toLong() * growthFlavor * maxOf(1, civInfo.cities.size / 3)
                    if (trait == MILITARISTIC && !ai.isGoingForWorldConquest())
                        value += rules.define(AiDefine.MC_GIFT_WEIGHT_MILITARISTIC)
                    value += MinorCivHelpers.numResourcesMajorLacks(cityState, civInfo).toLong() * rules.define(AiDefine.MC_GIFT_WEIGHT_RESOURCE_WE_NEED)
                    if (approach == MinorCivApproach.Protective) value += rules.define(AiDefine.MC_GIFT_WEIGHT_PROTECTIVE)
                    if (cityState.cityStatePersonality == CityStatePersonality.Hostile) value += rules.define(AiDefine.MC_GIFT_WEIGHT_HOSTILE)
                    value += when (civInfo.getProximity(cityState)) {
                        Proximity.Neighbors -> rules.define(AiDefine.MC_GIFT_WEIGHT_NEIGHBORS)
                        Proximity.Close -> rules.define(AiDefine.MC_GIFT_WEIGHT_CLOSE)
                        Proximity.Far -> rules.define(AiDefine.MC_GIFT_WEIGHT_FAR)
                        else -> 0
                    }
                    if (MinorCivHelpers.isRecentlyBulliedByMajor(cityState, civInfo)) value -= 100
                    if (isAlly) {
                        if (!MinorCivHelpers.isCloseToNotBeingAllies(cityState, civInfo)) value = 0
                    } else value += influence
                }
                if (ai.isGoingForWorldConquest() && cityState.cityStateType.name == MILITARISTIC) value += 999
            }
            CityStatePlanKind.CollectVotes -> {
                if (isAlly) {
                    if (!MinorCivHelpers.isCloseToNotBeingAllies(cityState, civInfo)) value = 0
                } else value += if (cityState.allyCiv != null) influence else influence * 2
            }
            CityStatePlanKind.AttackToPreventDefeat, CityStatePlanKind.DefendForWin -> {
                val threat = plan.diploThreat!!
                if (cityState.allyCiv == threat)
                    value = MAX_INT - MinorCivHelpers.influence(cityState, threat)
            }
        }
        return value
    }

    /**
     * CvEspionageAI::GetCityStatePlan: once the United Nations countdown has started, a civilization allied with at least half of the
     * city-states makes us attack its allies (or defend ours if it is us); with at least a quarter, we collect votes.
     */
    fun getCityStatePlan(): CityStatePlan {
        if (!espionage.unCountdownStarted) return CityStatePlan(CityStatePlanKind.PlayNormal, null)
        val cityStates = gameInfo.civilizations.filter { it.isCityState && it.isAlive() }
        var threat: Civilization? = null
        var maxVotes = 0
        for (major in gameInfo.civilizations) {
            if (!major.isMajorCiv) continue
            val votes = cityStates.count { it.allyCiv == major }
            if (votes > maxVotes) {
                maxVotes = votes
                threat = major
            }
        }
        val kind = when {
            threat == null -> CityStatePlanKind.PlayNormal
            maxVotes >= cityStates.size / 2 -> if (threat == civInfo) CityStatePlanKind.DefendForWin else CityStatePlanKind.AttackToPreventDefeat
            maxVotes >= cityStates.size / 4 -> CityStatePlanKind.CollectVotes
            else -> CityStatePlanKind.PlayNormal
        }
        return CityStatePlan(kind, threat)
    }
    //endregion

    enum class CityStatePlanKind { PlayNormal, CollectVotes, AttackToPreventDefeat, DefendForWin }
    class CityStatePlan(val kind: CityStatePlanKind, val diploThreat: Civilization?)

    companion object {
        /** MAX_INT of the original's scores */
        private const val MAX_INT = Int.MAX_VALUE.toLong()
        private const val MARITIME = "Maritime"
        private const val MILITARISTIC = "Militaristic"

        /** CvEspionageAI::EvaluateSpiesAssignedToTargetPlayer: after promising [other] to stop spying, our spies there reconsider their post */
        fun onAgreedToStopSpying(civ: Civilization, other: Civilization) {
            for (spy in civ.espionageManager.spyList)
                if (spy.isAlive() && spy.getCityOrNull()?.civ == other) spy.evaluateReassignment = true
        }
    }
}
