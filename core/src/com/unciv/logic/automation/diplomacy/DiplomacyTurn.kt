package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import kotlin.math.max
import kotlin.math.min

/**
 *  The assessment part of CvDiplomacyAI::DoTurn, run for every major civilization (humans too, as the original) at the start of its turn,
 *  after the grand strategy and the war states ([WarStateAi]): counters, war damage, economic strength, warmonger and military threats,
 *  war projections and goals, peace treaty willingness, disputes, promises, postures, then opinions ([OpinionAi]) and approaches ([ApproachAi]).
 *  What the AI then does (MakeWar, demands, exchanges, contacting players) runs in its own turn, see [DiplomacyAutomation].
 *
 *  Order notes: CvDiplomacyAI::DoCounters ends the original's turn; here it starts the next one, which gives the same sequence.
 *  Military strengths, target values and war states come from [WarStateAi], updated just before this.
 */
object DiplomacyTurn {

    fun doTurn(civ: Civilization) {
        val rules = civ.gameInfo.ruleset.ai
        val others = validPlayers(civ)
        doCounters(civ, others, rules)
        warDamageDecay(civ, others)
        updateWarDamageLevels(civ, others, rules)
        updateEconomicStrengths(civ, others, rules)
        updateWarmongerThreats(civ, others, rules)
        updateMilitaryThreats(civ, others, rules)
        updateWarProjections(civ, others, rules)
        updateWarGoals(civ, others, rules)
        updatePeaceTreatyWillingness(civ, others, rules)
        DisputeAi.updateLandDisputes(civ, others, rules)
        DisputeAi.updateWonderDisputes(civ, others, rules)
        DisputeAi.updateMinorCivDisputes(civ, others, rules)
        PromiseAi.testPromises(civ, others, rules)
        updateTheirWarDamageLevels(civ, others, rules)
        DisputeAi.updateMilitaryPostures(civ, others, rules)
        DisputeAi.updateExpansionPostures(civ, others, rules)
        DisputeAi.updatePlotBuyingPostures(civ, others, rules)
        OpinionAi.updateOpinions(civ, others, rules)
        ApproachAi.updateApproaches(civ, others, rules)
        com.unciv.logic.automation.civilization.CityStateAi.updateMinorCivApproaches(civ)
        civ.ai.diplomacy.lastTotalLandDisputeLevel = DisputeAi.totalLandDisputeLevel(civ, others, rules)
    }

    /** The players CvDiplomacyAI::IsPlayerValid accepts: alive, with cities, met (majors and city-states) */
    fun validPlayers(civ: Civilization): List<Civilization> =
        civ.gameInfo.civilizations.filter { WarStateAi.isPlayerValid(civ, it) }

    /** PlayerProximityTypes value of the original: DISTANT 0, FAR 1, CLOSE 2, NEIGHBORS 3, no cities -1 */
    fun proximity(civ: Civilization, other: Civilization): Int = WarStateAi.proximityRank(civ.getProximity(other))

    const val PROXIMITY_DISTANT = 0
    const val PROXIMITY_FAR = 1
    const val PROXIMITY_CLOSE = 2
    const val PROXIMITY_NEIGHBORS = 3

    /** GameSpeeds.RelationshipDuration of the game's speed (DoF and denouncement length) */
    fun relationshipDuration(civ: Civilization): Int = civ.gameInfo.ruleset.ai.gameSpeedDiplomacy(civ.gameInfo.speed.name).relationshipDuration

    /** GameSpeeds.OpinionDurationPercent of the game's speed */
    fun opinionDurationPercent(civ: Civilization): Int = civ.gameInfo.ruleset.ai.gameSpeedDiplomacy(civ.gameInfo.speed.name).opinionDurationPercent

    /** CvGame::getCurrentEra: the average era of the living civilizations (city-states included, as the original's teams) */
    fun gameEraName(civ: Civilization): String {
        var sum = 0
        var count = 0
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive()) continue
            sum += other.getEraNumber()
            count++
        }
        val eraNumber = if (count == 0) 0 else sum / count
        return civ.gameInfo.ruleset.eras.values.firstOrNull { it.eraNumber == eraNumber }?.name ?: ""
    }

    //region CvDiplomacyAI::DoCounters

    private fun doCounters(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val dealDecay = rules.define(AiDefine.DEAL_VALUE_PER_TURN_DECAY)
        val relationshipDuration = relationshipDuration(civ)
        for (other in others) {
            val memory = ai.memoryOf(other)
            if (memory.turnsLockedIntoWar > 0) memory.turnsLockedIntoWar--   // CvTeam::doTurn
            if (!other.isMajorCiv) continue
            memory.recentTradeValue = max(0, memory.recentTradeValue - dealDecay)
            memory.commonFoeValue = max(0, memory.commonFoeValue - rules.define(AiDefine.COMMON_FOE_VALUE_PER_TURN_DECAY))
            if (memory.recentAssistValue > 0) memory.recentAssistValue -= min(memory.recentAssistValue, dealDecay)
            else if (memory.recentAssistValue < 0) memory.recentAssistValue += min(-memory.recentAssistValue, dealDecay)
            PromiseAi.decayPromiseValues(memory, rules)
            memory.declaredWarOnFriendValue = max(0, memory.declaredWarOnFriendValue - rules.define(AiDefine.DECLARED_WAR_ON_FRIEND_PER_TURN_DECAY))

            if (memory.turnsSinceAttackedProtectedMinor >= 0) memory.turnsSinceAttackedProtectedMinor++
            if (memory.turnsSinceKilledProtectedMinor >= 0) memory.turnsSinceKilledProtectedMinor++
            if (memory.turnsSinceBulliedProtectedMinor >= 0) memory.turnsSinceBulliedProtectedMinor++
            if (memory.turnsSinceSidedWithProtectedMinor >= 0) memory.turnsSinceSidedWithProtectedMinor++
            if (memory.demandCounter > -1) memory.demandCounter++
            if (memory.doFCounter > -1) memory.doFCounter++
            if (memory.denouncedCounter > -1) memory.denouncedCounter++

            // The denouncement ran out (Unciv keeps it as a flag lasting RelationshipDuration turns)
            val diplomacy = civ.getDiplomacyManager(other) ?: continue
            if (memory.denouncementActive && !diplomacy.hasFlag(DiplomacyFlags.Denunciation)) {
                memory.denouncementActive = false
                memory.denouncedCounter = -1
                memory.doFCounter = -1
                val theirMemory = other.ai.diplomacy.memoryOf(civ)
                theirMemory.doFCounter = -1
                theirMemory.friendDenouncedUs = false
                for (entry in memory.coopWars.values) if (entry.counter < -1) entry.counter = -1
                for (entry in theirMemory.coopWars.values) entry.counter = -1
            }
            // The friendship ran out
            if (memory.doFCounter >= relationshipDuration && !diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                memory.doFCounter = -1
        }
    }
    //endregion

    //region War damage (CvDiplomacyAI::DoWarDamageDecay, DoUpdateWarDamageLevel, DoUpdateOtherPlayerWarDamageLevel)

    private fun warDamageDecay(civ: Civilization, others: List<Civilization>) {
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            if (civ.isAtWarWith(other)) continue
            // Goes down by 1/20th every turn at peace, at least 1
            if (memory.warValueLost > 0) memory.warValueLost = max(0, memory.warValueLost - max(1, memory.warValueLost / 20))
            if (memory.theirWarValueLostToUs > 0)
                memory.theirWarValueLostToUs = max(0, memory.theirWarValueLostToUs - max(1, memory.theirWarValueLostToUs / 20))
        }
    }

    /** CvDiplomacyAI::DoUpdateWarDamageLevel: what we lost to each player, against what we have left (cities by population, units by power) */
    private fun updateWarDamageLevels(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        var currentValue = 0
        for (city in civ.cities) {
            currentValue += city.population.population * rules.define(AiDefine.WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER)
            if (city.isOriginalCapital) currentValue = currentValue * 3 / 2   // as the original: scales the running total
        }
        val typicalPower = powerOfStrongestBuildableLandUnit(civ)
        for (unit in civ.units.getCivUnits()) currentValue += warValueOfUnit(unit.baseUnit.getForceEvaluation(), typicalPower, rules)
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val lost = memory.warValueLost
            memory.warDamageLevel = if (lost <= 0) WarDamageLevel.None else {
                val ratio = if (currentValue > 0) lost * 100 / (currentValue + lost) else lost
                warDamageLevelOf(ratio, rules)
            }
        }
    }

    private fun warDamageLevelOf(ratio: Int, rules: AiRuleset): WarDamageLevel = when {
        ratio >= rules.define(AiDefine.WAR_DAMAGE_LEVEL_THRESHOLD_CRIPPLED) -> WarDamageLevel.Crippled
        ratio >= rules.define(AiDefine.WAR_DAMAGE_LEVEL_THRESHOLD_SERIOUS) -> WarDamageLevel.Serious
        ratio >= rules.define(AiDefine.WAR_DAMAGE_LEVEL_THRESHOLD_MAJOR) -> WarDamageLevel.Major
        ratio >= rules.define(AiDefine.WAR_DAMAGE_LEVEL_THRESHOLD_MINOR) -> WarDamageLevel.Minor
        else -> WarDamageLevel.None
    }

    /** CvUnit::kill's war value of a unit: its power against the strongest land unit the killer can build (100 = that unit) */
    fun warValueOfUnit(power: Int, typicalPower: Int, rules: AiRuleset): Int =
        if (typicalPower > 0) power * rules.define(AiDefine.DEFAULT_WAR_VALUE_FOR_UNIT) / typicalPower
        else rules.define(AiDefine.DEFAULT_WAR_VALUE_FOR_UNIT)

    /** CvMilitaryAI::GetPowerOfStrongestBuildableUnit(DOMAIN_LAND) */
    fun powerOfStrongestBuildableLandUnit(civ: Civilization): Int {
        var best = 0
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!unit.isLandUnit || !unit.isMilitary) continue
            val power = unit.getForceEvaluation()
            if (power > best && unit.isBuildable(civ)) best = power
        }
        return best
    }

    /** CvDiplomacyAI::DoUpdateOtherPlayerWarDamageLevel, for the pairs the original reads (their losses to us, GetWarScore) */
    private fun updateTheirWarDamageLevels(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            if (!other.isMajorCiv) continue
            val memory = civ.ai.diplomacy.memoryOf(other)
            if (!civ.isAtWarWith(other)) {
                memory.theirWarDamageLevel = WarDamageLevel.None
                continue
            }
            var currentValue = 0
            for (city in other.cities) {
                currentValue += city.population.population * rules.define(AiDefine.WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER)
                if (city.isOriginalCapital) currentValue = currentValue * 3 / 2
            }
            currentValue = max(1, currentValue)
            memory.theirWarDamageLevel = warDamageLevelOf(memory.theirWarValueLostToUs * 100 / currentValue, rules)
        }
    }
    //endregion

    //region Strengths and threats

    /** CvDiplomacyAI::DoUpdateOnePlayerEconomicStrength */
    private fun updateEconomicStrengths(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val ours = WarStateAi.economicMight(civ)
        val multiplier = rules.define(AiDefine.ECONOMIC_STRENGTH_RATIO_MULTIPLIER)
        for (other in others) {
            val ratio = if (ours > 0) WarStateAi.economicMight(other) * multiplier / ours else multiplier * 5
            civ.ai.diplomacy.memoryOf(other).economicStrength = when {
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_IMMENSE_THRESHOLD) -> StrengthLevel.Immense
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_POWERFUL_THRESHOLD) -> StrengthLevel.Powerful
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_STRONG_THRESHOLD) -> StrengthLevel.Strong
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_AVERAGE_THRESHOLD) -> StrengthLevel.Average
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_POOR_THRESHOLD) -> StrengthLevel.Poor
                ratio >= rules.define(AiDefine.ECONOMIC_STRENGTH_WEAK_THRESHOLD) -> StrengthLevel.Weak
                else -> StrengthLevel.Pathetic
            }
        }
    }

    /** CvDiplomacyAI::GetOtherPlayerWarmongerScore: the warmonger amount × WarmongerHate / 100 */
    fun warmongerScore(civ: Civilization, other: Civilization): Int =
        civ.ai.diplomacy.memoryOf(other).warmongerAmount * civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate) / 100

    /** CvDiplomacyAI::DoUpdateWarmongerThreats, with the decay of the warmonger amounts */
    private fun updateWarmongerThreats(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val playersEver = civ.gameInfo.civilizations.count { !it.isBarbarian && !it.isSpectator }
        val warmongerHate = civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate)
        for (other in others) {
            if (!other.isMajorCiv) continue
            val memory = civ.ai.diplomacy.memoryOf(other)
            val score = warmongerScore(civ, other)
            var threat = when {
                score >= rules.define(AiDefine.WARMONGER_THREAT_CRITICAL_THRESHOLD) -> AiThreat.Critical
                score >= rules.define(AiDefine.WARMONGER_THREAT_SEVERE_THRESHOLD) -> AiThreat.Severe
                score >= rules.define(AiDefine.WARMONGER_THREAT_MAJOR_THRESHOLD) -> AiThreat.Major
                score >= rules.define(AiDefine.WARMONGER_THREAT_MINOR_THRESHOLD) -> AiThreat.Minor
                else -> AiThreat.None
            }
            // Share of the players ever in the game they have killed, adjusted ±50% by WarmongerHate
            val aggression = civ.ai.aggressionOf(other)
            val killed = aggression.minorsConquered + aggression.majorsConquered
            if (killed > 0 && playersEver > 0) {
                var percent = killed * 100 / playersEver
                val mod = (warmongerHate - rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)) * rules.define(AiDefine.WARMONGER_THREAT_PERSONALITY_MOD)
                percent += percent * mod / 100
                // As the original: the severe test runs last, so it overrides a critical result
                if (percent >= rules.define(AiDefine.WARMONGER_THREAT_CRITICAL_PERCENT_THRESHOLD)) threat = AiThreat.Critical
                if (percent >= rules.define(AiDefine.WARMONGER_THREAT_SEVERE_PERCENT_THRESHOLD)) threat = AiThreat.Severe
            }
            memory.warmongerThreat = threat
            memory.warmongerAmount = max(0, memory.warmongerAmount + rules.define(AiDefine.WARMONGER_THREAT_PER_TURN_DECAY))
        }
    }

    /** CvDiplomacyAI::DoUpdateMilitaryThreats */
    private fun updateMilitaryThreats(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        var myStrength = max(1, WarStateAi.getPower(civ))
        for (city in civ.cities) {
            var cityMod = WarStateAi.cityPower(city) * city.health / city.getMaxHealth()
            cityMod /= 100
            cityMod = cityMod * rules.define(AiDefine.MILITARY_STRENGTH_CITY_MOD) / 100
            myStrength += cityMod
        }
        for (other in others) {
            if (!other.isMajorCiv) continue
            var threat = WarStateAi.getPower(other) * rules.define(AiDefine.MILITARY_STRENGTH_RATIO_MULTIPLIER) / myStrength
            val atWar = civ.isAtWarWith(other)
            if (atWar) threat += when (WarStateAi.warState(civ, other)) {
                WarState.Calm -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_CALM)
                WarState.NearlyWon -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_NEARLY_WON)
                WarState.Offensive -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_OFFENSIVE)
                WarState.Stalemate -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_STALEMATE)
                WarState.Defensive -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_DEFENSIVE)
                WarState.NearlyDefeated -> rules.define(AiDefine.MILITARY_THREAT_WAR_STATE_NEARLY_DEFEATED)
                null -> 0
            }
            threat += when (proximity(civ, other)) {
                PROXIMITY_NEIGHBORS -> rules.define(AiDefine.MILITARY_THREAT_NEIGHBORS)
                PROXIMITY_CLOSE -> rules.define(AiDefine.MILITARY_THREAT_CLOSE)
                PROXIMITY_FAR -> rules.define(AiDefine.MILITARY_THREAT_FAR)
                PROXIMITY_DISTANT -> rules.define(AiDefine.MILITARY_THREAT_DISTANT)
                else -> 0
            }
            if (!atWar) {
                val aggression = civ.ai.aggressionOf(other)
                threat += aggression.minorsAttacked * rules.define(AiDefine.MILITARY_THREAT_PER_MINOR_ATTACKED)
                threat += aggression.minorsConquered * rules.define(AiDefine.MILITARY_THREAT_PER_MINOR_CONQUERED)
                threat += aggression.majorsAttacked * rules.define(AiDefine.MILITARY_THREAT_PER_MAJOR_ATTACKED)
                threat += aggression.majorsConquered * rules.define(AiDefine.MILITARY_THREAT_PER_MAJOR_CONQUERED)
                // Reduced (dramatically) if they are at war with others: CvTeam::getAtWarCount(true) counts wars with majors
                val warCount = other.getCivsAtWarWith().count { it.isMajorCiv }
                if (warCount > 0) threat += rules.define(AiDefine.MILITARY_THREAT_ALREADY_WAR_EACH_PLAYER_MULTIPLIER) * warCount * threat / 100
            }
            civ.ai.diplomacy.memoryOf(other).militaryThreat = when {
                threat >= rules.define(AiDefine.MILITARY_THREAT_CRITICAL_THRESHOLD) -> AiThreat.Critical
                threat >= rules.define(AiDefine.MILITARY_THREAT_SEVERE_THRESHOLD) -> AiThreat.Severe
                threat >= rules.define(AiDefine.MILITARY_THREAT_MAJOR_THRESHOLD) -> AiThreat.Major
                threat >= rules.define(AiDefine.MILITARY_THREAT_MINOR_THRESHOLD) -> AiThreat.Minor
                else -> AiThreat.None
            }
        }
    }
    //endregion

    //region War projection, goals, peace (CvDiplomacyAI::DoUpdateWarProjections, GetWarScore, DoUpdateWarGoals, DoUpdatePeaceTreatyWillingness)

    private fun updateWarProjections(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val score = warScore(civ, other, rules)
            var projection = when {
                score >= rules.define(AiDefine.WAR_PROJECTION_THRESHOLD_VERY_GOOD) -> WarProjection.VeryGood
                score >= rules.define(AiDefine.WAR_PROJECTION_THRESHOLD_GOOD) -> WarProjection.Good
                score <= rules.define(AiDefine.WAR_PROJECTION_THRESHOLD_DESTRUCTION) -> WarProjection.Destruction
                score <= rules.define(AiDefine.WAR_PROJECTION_THRESHOLD_DEFEAT) -> WarProjection.Defeat
                score <= rules.define(AiDefine.WAR_PROJECTION_THRESHOLD_STALEMATE) -> WarProjection.Stalemate
                else -> WarProjection.Unknown
            }
            // A bad target can at best be a stalemate
            val target = WarStateAi.targetValue(civ, other)
            if (target != null && target <= TargetValue.Bad && projection >= WarProjection.Good) projection = WarProjection.Stalemate
            memory.lastWarProjection = memory.warProjection ?: WarProjection.Unknown
            memory.warProjection = projection
        }
    }

    /** CvDiplomacyAI::GetWarScore: how a war against [other] would go */
    fun warScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memoryOf(other)
        var score = when (WarStateAi.militaryStrengthComparedToUs(civ, other)) {
            StrengthLevel.Pathetic -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_PATHETIC)
            StrengthLevel.Weak -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_WEAK)
            StrengthLevel.Poor -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_POOR)
            StrengthLevel.Average -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_AVERAGE)
            StrengthLevel.Strong -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_STRONG)
            StrengthLevel.Powerful -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_POWERFUL)
            StrengthLevel.Immense -> rules.define(AiDefine.WAR_PROJECTION_THEIR_MILITARY_STRENGTH_IMMENSE)
            null -> 0
        }
        score += when (memory.economicStrength) {
            StrengthLevel.Pathetic -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_PATHETIC)
            StrengthLevel.Weak -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_WEAK)
            StrengthLevel.Poor -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_POOR)
            StrengthLevel.Average -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_AVERAGE)
            StrengthLevel.Strong -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_STRONG)
            StrengthLevel.Powerful -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_POWERFUL)
            StrengthLevel.Immense -> rules.define(AiDefine.WAR_PROJECTION_THEIR_ECONOMIC_STRENGTH_IMMENSE)
            null -> 0
        }
        score += when (memory.warDamageLevel) {
            // If they're expanding recklessly they're a better target - unless they have hurt us
            WarDamageLevel.None -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_US_NONE) +
                if (WarStateAi.isPlayerRecklessExpander(civ, other)) rules.define(AiDefine.WAR_PROJECTION_RECKLESS_EXPANDER) else 0
            WarDamageLevel.Minor -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_US_MINOR)
            WarDamageLevel.Major -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_US_MAJOR)
            WarDamageLevel.Serious -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_US_SERIOUS)
            WarDamageLevel.Crippled -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_US_CRIPPLED)
        }
        score += when (memory.theirWarDamageLevel) {
            WarDamageLevel.None -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_THEM_NONE)
            WarDamageLevel.Minor -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_THEM_MINOR)
            WarDamageLevel.Major -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_THEM_MAJOR)
            WarDamageLevel.Serious -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_THEM_SERIOUS)
            WarDamageLevel.Crippled -> rules.define(AiDefine.WAR_PROJECTION_WAR_DAMAGE_THEM_CRIPPLED)
        }
        // The intangibles: our score against theirs, both at least 100, ratio within ±50
        val ourScore = max(100, civ.calculateTotalScore().toInt())
        val theirScore = max(100, other.calculateTotalScore().toInt())
        score += ((ourScore - theirScore) * 100 / min(ourScore, theirScore)).coerceIn(-50, 50)
        // Long wars wear down: -1 per 3 turns, at most WAR_PROJECTION_WAR_DURATION_SCORE_CAP
        score -= min(WarStateAi.turnsAtWar(civ, other) / 3, rules.define(AiDefine.WAR_PROJECTION_WAR_DURATION_SCORE_CAP))
        return score
    }

    /** CvDiplomacyAI::DoUpdateWarGoals, with the want-peace counter */
    private fun updateWarGoals(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val worldConquest = civ.ai.isGoingForWorldConquest()
        val ai = civ.ai.diplomacy
        val threshold = rules.define(AiDefine.REQUEST_PEACE_TURN_THRESHOLD)
        for (other in others) {
            val memory = ai.memoryOf(other)
            var goal: WarGoal? = null
            if (civ.isAtWarWith(other)) {
                val isMinor = other.isCityState
                val higherUpsWantWar = if (isMinor) civ.ai.minorCivApproaches[other.civID] == MinorCivApproach.Conquest
                    else ai.approachTowards(other) == MajorCivApproach.War
                if (higherUpsWantWar) {
                    goal = if (isMinor) {
                        if (worldConquest) WarGoal.Conquest else WarGoal.Damage
                    } else {
                        var majorGoal = WarGoal.Damage
                        if (CoopWarAi.isLockedIntoCoopWar(civ, other, rules)) majorGoal = WarGoal.Conquest
                        val projection = memory.warProjection
                        if (projection != null && projection >= WarProjection.Unknown &&
                            (memory.opinion == MajorCivOpinion.Unforgivable || worldConquest)) majorGoal = WarGoal.Conquest
                        majorGoal
                    }
                } else {
                    // About to cause mayhem: hold off peace a bit (not against minors)
                    goal = if (!isMinor && WarStateAi.warState(civ, other) == WarState.NearlyWon && WarStateAi.stateAllWars(civ) != StateAllWars.Losing)
                        WarGoal.Damage else WarGoal.Peace
                }
            } else if (memory.warGoal == WarGoal.Prepare) goal = WarGoal.Prepare
            else if (memory.warGoal == WarGoal.Demand) goal = WarGoal.Demand

            if (goal == WarGoal.Peace) memory.wantPeaceCounter++ else memory.wantPeaceCounter = 0
            memory.warGoal = goal
            // CvDiplomacyAI::IsWantsPeaceWithPlayer (canChangeWarPeace: Unciv has no forced wars or peace)
            memory.wantsPeace = memory.wantPeaceCounter >= threshold
        }
    }

    /** CvDiplomacyAI::IsWillingToMakePeaceWithHuman: at war at least 5 turns and neither side locked into the war */
    private fun isWillingToMakePeaceWithHuman(civ: Civilization, human: Civilization): Boolean {
        if (civ.ai.diplomacy.memoryOf(human).turnsLockedIntoWar > 1) return false
        if (human.ai.diplomacy.memoryOf(civ).turnsLockedIntoWar > 1) return false
        return WarStateAi.turnsAtWar(civ, human) >= 5
    }

    private fun updatePeaceTreatyWillingness(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            if (!other.isMajorCiv) continue
            val memory = civ.ai.diplomacy.memoryOf(other)
            memory.treatyWillingToOffer = PeaceTreatyType.None
            memory.treatyWillingToAccept = PeaceTreatyType.None
            if (!civ.isAtWarWith(other)) continue
            if (other.isHuman() && !isWillingToMakePeaceWithHuman(civ, other)) continue
            // Out for conquest: no peace
            if (memory.warGoal == WarGoal.Conquest) continue
            val projection = memory.warProjection

            // What we are willing to give up
            var offer = when (projection) {
                WarProjection.Destruction -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_DESTRUCTION)
                WarProjection.Defeat -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_DEFEAT)
                WarProjection.Stalemate -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_STALEMATE)
                WarProjection.Unknown -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_UNKNOWN)
                WarProjection.Good -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_GOOD)
                WarProjection.VeryGood -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_PROJECTION_VERY_GOOD)
                null -> 0
            }
            offer += peaceWarDamageWeight(memory.warDamageLevel, rules)
            offer -= peaceWarDamageWeight(other.ai.diplomacy.memoryOf(civ).warDamageLevel, rules)
            memory.treatyWillingToOffer = when {
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_UN_SURRENDER) -> PeaceTreatyType.UnconditionalSurrender
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_CAPITULATION) -> PeaceTreatyType.Capitulation
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_CESSION) -> PeaceTreatyType.Cession
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SURRENDER) -> PeaceTreatyType.Surrender
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SUBMISSION) -> PeaceTreatyType.Submission
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_BACKDOWN) -> PeaceTreatyType.Backdown
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SETTLEMENT) -> PeaceTreatyType.Settlement
                offer >= rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_ARMISTICE) -> PeaceTreatyType.Armistice
                else -> PeaceTreatyType.WhitePeace
            }
            // (CvTeam::IsHasBrokenPeaceTreaty caps this at white peace; Unciv has no way to break a peace treaty)

            // What we want from them
            var accept = when (projection) {
                WarProjection.Destruction -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_DESTRUCTION)
                WarProjection.Defeat -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_DEFEAT)
                WarProjection.Stalemate -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_STALEMATE)
                WarProjection.Unknown -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_UNKNOWN)
                WarProjection.Good -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_GOOD)
                WarProjection.VeryGood -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_PROJECTION_VERY_GOOD)
                null -> 0
            }
            accept += when (WarStateAi.targetValue(civ, other)) {
                TargetValue.Impossible -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_TARGET_IMPOSSIBLE)
                TargetValue.Bad -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_TARGET_BAD)
                TargetValue.Average -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_TARGET_AVERAGE)
                TargetValue.Favorable -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_TARGET_FAVORABLE)
                TargetValue.Soft -> rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_TARGET_SOFT)
                null -> 0
            }
            memory.treatyWillingToAccept = when {
                WarStateAi.stateAllWars(civ) == StateAllWars.Losing -> PeaceTreatyType.WhitePeace
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_UN_SURRENDER) -> PeaceTreatyType.UnconditionalSurrender
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_CAPITULATION) -> PeaceTreatyType.Capitulation
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_CESSION) -> PeaceTreatyType.Cession
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SURRENDER) -> PeaceTreatyType.Surrender
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SUBMISSION) -> PeaceTreatyType.Submission
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_BACKDOWN) -> PeaceTreatyType.Backdown
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SETTLEMENT) -> PeaceTreatyType.Settlement
                accept >= rules.define(AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_ARMISTICE) -> PeaceTreatyType.Armistice
                else -> PeaceTreatyType.WhitePeace
            }
        }
    }

    private fun peaceWarDamageWeight(level: WarDamageLevel, rules: AiRuleset): Int = when (level) {
        WarDamageLevel.None -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_WAR_DAMAGE_NONE)
        WarDamageLevel.Minor -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_WAR_DAMAGE_MINOR)
        WarDamageLevel.Major -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_WAR_DAMAGE_MAJOR)
        WarDamageLevel.Serious -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_WAR_DAMAGE_SERIOUS)
        WarDamageLevel.Crippled -> rules.define(AiDefine.PEACE_WILLINGNESS_OFFER_WAR_DAMAGE_CRIPPLED)
    }
    //endregion
}
