package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import kotlin.random.Random

/**
 *  CvDiplomacyAI::DoUpdateMajorCivApproaches and GetBestApproachTowardsMajorCiv: each turn every approach towards a major is chosen anew,
 *  worst opinion first (ties: strongest best weight first), so later choices see the earlier ones.
 */
object ApproachAi {

    private val APPROACHES = MajorCivApproach.entries
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val AFRAID = MajorCivApproach.Afraid.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal
    private val NEUTRAL = MajorCivApproach.Neutral.ordinal

    /** The outcome of [bestApproach]: the approach, its weight, and the war face it would wear (null unless War) */
    class BestApproach(val approach: MajorCivApproach, val weight: Int, val warFace: WarFace?)

    /** CvDiplomacyAI::DoUpdateMajorCivApproaches */
    fun updateApproaches(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val majors = others.filter { it.isMajorCiv }
        // Last turn's approaches go to the scratch pad; the real ones are cleared so the order of this turn's choices is visible
        val oldApproaches = HashMap<String, MajorCivApproach?>(majors.size)
        for (other in majors) {
            val memory = ai.memoryOf(other)
            oldApproaches[other.civID] = memory.approach
            memory.approach = null
        }
        val random = civ.ai.random("DiplomacyAI.MajorCivApproach")
        val order = ArrayList<Civilization>(majors.size)
        for (opinion in MajorCivOpinion.entries) {
            val withOpinion = majors.filter { ai.memoryOf(it).opinion == opinion }
            if (withOpinion.size == 1) order.add(withOpinion[0])
            else if (withOpinion.size > 1)
                withOpinion.map { it to bestApproach(civ, it, oldApproaches[it.civID], lookAtOtherPlayers = false, rules, random).weight }
                    .sortedByDescending { it.second }.mapTo(order) { it.first }
        }
        for (other in order) {
            val best = bestApproach(civ, other, oldApproaches[other.civID], lookAtOtherPlayers = true, rules, random)
            val memory = ai.memoryOf(other)
            if (best.approach == MajorCivApproach.War && memory.warFace == null) memory.warFace = best.warFace
            memory.approach = best.approach
        }
    }

    /** DoFirstContactInitRelationship: an approach right when we meet (the others keep theirs) */
    fun initApproach(civ: Civilization, other: Civilization, rules: AiRuleset) {
        val memory = civ.ai.diplomacy.memoryOf(other)
        val best = bestApproach(civ, other, memory.approach, lookAtOtherPlayers = true, rules, civ.ai.random("DiplomacyAI.FirstContact" + other.civID))
        if (best.approach == MajorCivApproach.War && memory.warFace == null) memory.warFace = best.warFace
        memory.approach = best.approach
    }

    /**
     *  CvDiplomacyAI::GetBestApproachTowardsMajorCiv. [oldApproach] is last turn's approach (the scratch pad), [random] draws the random part
     *  (APPROACH_RANDOM_PERCENT of each positive weight).
     */
    fun bestApproach(civ: Civilization, other: Civilization, oldApproach: MajorCivApproach?, lookAtOtherPlayers: Boolean,
                     rules: AiRuleset, random: Random): BestApproach {
        val weights = approachWeights(civ, other, oldApproach, lookAtOtherPlayers, rules)
        // Random factor: weights in hundreds, plus up to APPROACH_RANDOM_PERCENT of a positive weight
        for (i in weights.indices) {
            weights[i] *= 100
            var randAmount = weights[i] * rules.define(AiDefine.APPROACH_RANDOM_PERCENT)
            if (randAmount > 0) {
                randAmount /= 100
                if (randAmount > 0) weights[i] += random.nextInt(randAmount)
            }
        }
        applyFinalRules(civ, other, weights, rules)
        return pickApproach(civ, other, weights)
    }

    /** The weights of GetBestApproachTowardsMajorCiv before the random part, by [MajorCivApproach.ordinal] */
    fun approachWeights(civ: Civilization, other: Civilization, oldApproach: MajorCivApproach?, lookAtOtherPlayers: Boolean, rules: AiRuleset): IntArray {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val w = IntArray(APPROACHES.size)

        // Neutral default
        w[NEUTRAL] += rules.define(AiDefine.APPROACH_NEUTRAL_DEFAULT)

        // Current approach biases: stay where we are
        val old = oldApproach ?: MajorCivApproach.Neutral
        w[old.ordinal] += rules.define(AiDefine.APPROACH_BIAS_FOR_CURRENT)
        if (old == MajorCivApproach.Deceptive) w[WAR] += rules.define(AiDefine.APPROACH_WAR_CURRENTLY_DECEPTIVE)
        if (old == MajorCivApproach.Hostile) w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_CURRENTLY_HOSTILE)
        val warState = WarStateAi.warState(civ, other)
        // Wanted war last turn: the war must be going better than a stalemate
        if (warState != null && warState > WarState.Stalemate && old == MajorCivApproach.War) w[WAR] += rules.define(AiDefine.APPROACH_WAR_CURRENTLY_WAR)
        // Conquest grand strategy: not at war yet, or the war going better than defensive
        if ((warState == null || warState > WarState.Defensive) && civ.ai.isGoingForWorldConquest())
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_CONQUEST_GRAND_STRATEGY)

        // Personality
        for (approach in APPROACHES) w[approach.ordinal] += civ.ai.majorCivApproachBias(approach)

        // Opinion
        when (memory.opinion) {
            MajorCivOpinion.Unforgivable -> {
                w[WAR] += rules.define(AiDefine.APPROACH_OPINION_UNFORGIVABLE_WAR)
                w[HOSTILE] += rules.define(AiDefine.APPROACH_OPINION_UNFORGIVABLE_HOSTILE)
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_OPINION_UNFORGIVABLE_DECEPTIVE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_OPINION_UNFORGIVABLE_GUARDED)
            }
            MajorCivOpinion.Enemy -> {
                w[WAR] += rules.define(AiDefine.APPROACH_OPINION_ENEMY_WAR)
                w[HOSTILE] += rules.define(AiDefine.APPROACH_OPINION_ENEMY_HOSTILE)
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_OPINION_ENEMY_DECEPTIVE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_OPINION_ENEMY_GUARDED)
            }
            MajorCivOpinion.Competitor -> {
                w[WAR] += rules.define(AiDefine.APPROACH_OPINION_COMPETITOR_WAR)
                w[HOSTILE] += rules.define(AiDefine.APPROACH_OPINION_COMPETITOR_HOSTILE)
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_OPINION_COMPETITOR_DECEPTIVE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_OPINION_COMPETITOR_GUARDED)
            }
            MajorCivOpinion.Neutral -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_OPINION_NEUTRAL_DECEPTIVE)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_OPINION_NEUTRAL_FRIENDLY)
            }
            MajorCivOpinion.Favorable -> {
                w[HOSTILE] += rules.define(AiDefine.APPROACH_OPINION_FAVORABLE_HOSTILE)
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_OPINION_FAVORABLE_DECEPTIVE)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_OPINION_FAVORABLE_FRIENDLY)
            }
            MajorCivOpinion.Friend -> {
                w[HOSTILE] += rules.define(AiDefine.APPROACH_OPINION_FRIEND_HOSTILE)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_OPINION_FRIEND_FRIENDLY)
            }
            MajorCivOpinion.Ally -> w[FRIENDLY] += rules.define(AiDefine.APPROACH_OPINION_ALLY_FRIENDLY)
        }

        // Declaration of friendship
        if (OpinionAi.isDoFAccepted(civ, other)) {
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_WORKING_WITH_PLAYER)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_WORKING_WITH_PLAYER)
            w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_WORKING_WITH_PLAYER)
            w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_WORKING_WITH_PLAYER)
        }

        // Denouncements: we denounced them, a friend who denounced us, they denounced us
        val denouncements = (if (OpinionAi.isDenouncedPlayer(civ, other)) 1 else 0) + (if (memory.friendDenouncedUs) 1 else 0) +
            (if (OpinionAi.isDenouncedPlayer(other, civ)) 1 else 0)
        if (denouncements > 0) {
            w[WAR] += denouncements * rules.define(AiDefine.APPROACH_WAR_DENOUNCED)
            w[HOSTILE] += denouncements * rules.define(AiDefine.APPROACH_HOSTILE_DENOUNCED)
            w[GUARDED] += denouncements * rules.define(AiDefine.APPROACH_GUARDED_DENOUNCED)
            w[FRIENDLY] += denouncements * rules.define(AiDefine.APPROACH_FRIENDLY_DENOUNCED)
            w[DECEPTIVE] += denouncements * rules.define(AiDefine.APPROACH_DECEPTIVE_DENOUNCED)
        }

        // Mistreated our protected minors
        val killedAnger = OpinionAi.isAngryAboutProtectedMinorKilled(memory, rules)
        if (killedAnger || OpinionAi.isAngryAboutProtectedMinorAttacked(memory, rules)) {
            if (killedAnger || memory.turnsSinceAttackedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_RECENTLY_NUM_TURNS)) {
                w[WAR] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_WAR)
                w[HOSTILE] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_HOSTILE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_GUARDED)
            } else {
                w[WAR] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_PAST_WAR)
                w[HOSTILE] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_PAST_HOSTILE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_ATTACKED_PROTECTED_MINOR_PAST_GUARDED)
            }
        }

        // They made a demand of us
        if (memory.demandCounter != -1) {
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_DEMAND)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_DEMAND)
        }

        addPromiseWeights(civ, other, memory, w, rules)
        addMilitaryThreatWeights(memory.militaryThreat, w, rules)
        addNukeWeights(civ, other, w)

        // At war right now with someone else
        val stateAllWars = WarStateAi.stateAllWars(civ)
        for (third in civ.gameInfo.civilizations) {
            if (third == other || third == civ || !WarStateAi.isPlayerValid(civ, third) || !civ.isAtWarWith(third)) continue
            w[WAR] += when (stateAllWars) {
                StateAllWars.Neutral -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_NEUTRAL)
                StateAllWars.Winning -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_WINNING)
                StateAllWars.Losing -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_LOSING)
            }
            w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_AT_WAR_WITH_PLAYER)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_AT_WAR_WITH_PLAYER)
            w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_AT_WAR_WITH_PLAYER)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_AT_WAR_WITH_PLAYER)
        }

        // Approaches already given to players we feel more strongly about (this turn)
        if (lookAtOtherPlayers) {
            for (third in civ.gameInfo.civilizations) {
                if (third == other || third == civ || !WarStateAi.isPlayerValid(civ, third)) continue
                val thirdMemory = ai.memories[third.civID] ?: continue
                val thirdApproach = if (third.isMajorCiv) thirdMemory.approach else null
                if (thirdMemory.warGoal == WarGoal.Prepare || thirdApproach == MajorCivApproach.War) {
                    w[WAR] += rules.define(AiDefine.APPROACH_WAR_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                    w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                    w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                    w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                    w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                }
                when (thirdApproach) {
                    MajorCivApproach.Hostile -> {
                        w[WAR] += rules.define(AiDefine.APPROACH_WAR_HOSTILE_WITH_ANOTHER_PLAYER)
                        w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_HOSTILE_WITH_ANOTHER_PLAYER)
                        w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_HOSTILE_WITH_ANOTHER_PLAYER)
                        w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_HOSTILE_WITH_ANOTHER_PLAYER)
                    }
                    MajorCivApproach.Afraid -> {
                        w[WAR] += rules.define(AiDefine.APPROACH_WAR_AFRAID_WITH_ANOTHER_PLAYER)
                        w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_AFRAID_WITH_ANOTHER_PLAYER)
                        w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_AFRAID_WITH_ANOTHER_PLAYER)
                        w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_AFRAID_WITH_ANOTHER_PLAYER)
                    }
                    else -> {}
                }
            }
        }

        // Gold we get from trade routes with them (the original's untuned constants)
        val tradeGold = tradeGoldFrom(civ, other)
        if (tradeGold > 0) {
            w[WAR] -= 5
            w[HOSTILE] -= 5
            w[DECEPTIVE] += 5
            w[GUARDED] -= 1
            w[AFRAID] -= 1
            w[FRIENDLY] += 5
            w[NEUTRAL] -= 2
            // A war would leave us without income
            val goldRate = civ.stats.statsForNextTurn.gold.toInt()
            val delta = goldRate - tradeGold
            if (goldRate >= 0 && delta < 0) w[WAR] += delta
        }

        // War projection
        when (memory.warProjection) {
            WarProjection.Destruction -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DESTRUCTION_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DESTRUCTION_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_DESTRUCTION_PERCENT))
            }
            WarProjection.Defeat -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DEFEAT_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DEFEAT_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_DEFEAT_PERCENT))
            }
            WarProjection.Stalemate -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_STALEMATE_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_STALEMATE_PERCENT))
            }
            WarProjection.Unknown -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_UNKNOWN_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_UNKNOWN_PERCENT))
            }
            WarProjection.Good -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_GOOD_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_GOOD_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_GOOD_PERCENT))
            }
            WarProjection.VeryGood -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_VERY_GOOD_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_VERY_GOOD_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_VERY_GOOD_PERCENT))
            }
            null -> {}
        }

        // Reckless expander
        if (WarStateAi.isPlayerRecklessExpander(civ, other)) {
            scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_RECKLESS_EXPANDER))
            scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_RECKLESS_EXPANDER))
        }

        // Already in a war with someone else (their war states): dogpile
        val dogpiling = civ.gameInfo.civilizations.any {
            it.isMajorCiv && it != other && it != civ && WarStateAi.isPlayerValid(other, it) && WarStateAi.warState(other, it) != null
        }
        if (dogpiling) {
            scale(w, WAR, 200)
            scale(w, DECEPTIVE, 150)
        }

        // Distance
        val proximityPercent = when (DiplomacyTurn.proximity(civ, other)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_NEIGHBORS)
            DiplomacyTurn.PROXIMITY_CLOSE -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_CLOSE)
            DiplomacyTurn.PROXIMITY_FAR -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_FAR)
            DiplomacyTurn.PROXIMITY_DISTANT -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_DISTANT)
            else -> 100
        }
        scale(w, WAR, proximityPercent)
        scale(w, DECEPTIVE, proximityPercent)

        // Made peace recently
        if (memory.turnMadePeace > -1 && civ.gameInfo.turns - memory.turnMadePeace < rules.define(AiDefine.TURNS_SINCE_PEACE_WEIGHT_DAMPENER))
            scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_HAS_MADE_PEACE_BEFORE_PERCENT))

        // Duel: no friendly or deceptive
        if (civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } == 2) {
            w[DECEPTIVE] = 0
            w[FRIENDLY] = 0
        }

        // Agreed to a coop war against them
        if (CoopWarAi.isLockedIntoCoopWar(civ, other, rules)) w[WAR] += rules.define(AiDefine.COOP_WAR_LOCKED_TURNS_WAR_WEIGHT)
        return w
    }

    /** The checks after the random part: can't declare war, teammates (none in Unciv), human difficulty */
    private fun applyFinalRules(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        if (!civ.isAtWarWith(other) && civ.getDiplomacyManager(other)?.canDeclareWar() != true) w[WAR] = 0
        if (other.isHuman()) {
            val warMod = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get("AIDeclareWarProb") ?: 100
            scale(w, WAR, warMod)
            scale(w, DECEPTIVE, warMod)
        }
    }

    /** Sorts the approaches by weight (stable, so ties go to the original's order) and picks the war face from the runner-up */
    private fun pickApproach(civ: Civilization, other: Civilization, w: IntArray): BestApproach {
        var best = 0
        for (i in 1 until w.size) if (w[i] > w[best]) best = i
        val approach = APPROACHES[best]
        if (approach != MajorCivApproach.War) return BestApproach(approach, w[best], null)
        var warFace = civ.ai.diplomacy.memoryOf(other).warFace
        if (warFace == null) {
            var second = -1
            for (i in w.indices) if (i != best && (second < 0 || w[i] > w[second])) second = i
            warFace = when (APPROACHES[second]) {
                MajorCivApproach.Hostile -> WarFace.Hostile
                MajorCivApproach.Deceptive, MajorCivApproach.Afraid, MajorCivApproach.Friendly ->
                    if (OpinionAi.isDenouncedPlayer(civ, other)) WarFace.Neutral else WarFace.Friendly
                else -> WarFace.Neutral
            }
        }
        return BestApproach(approach, w[best], warFace)
    }

    private fun scale(w: IntArray, index: Int, percent: Int) {
        w[index] = w[index] * percent / 100
    }

    private fun addPromiseWeights(civ: Civilization, other: Civilization, memory: MajorCivMemory, w: IntArray, rules: AiRuleset) {
        if (memory.brokenMilitaryPromise) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_MILITARY_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_MILITARY_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_MILITARY_PROMISE)
        } else if (civ.gameInfo.civilizations.any { it.isMajorCiv && it.ai.diplomacy.memories[other.civID]?.brokenMilitaryPromise == true }) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_MILITARY_PROMISE_WORLD)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_MILITARY_PROMISE_WORLD)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_MILITARY_PROMISE_WORLD)
        } else if (memory.ignoredMilitaryPromise) {
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_MILITARY_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_MILITARY_PROMISE)
        }
        if (memory.brokenExpansionPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_EXPANSION_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_EXPANSION_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_EXPANSION_PROMISE)
        } else if (memory.ignoredExpansionPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_IGNORED_EXPANSION_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_EXPANSION_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_EXPANSION_PROMISE)
        }
        if (memory.brokenBorderPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_BORDER_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_BORDER_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_BORDER_PROMISE)
        } else if (memory.ignoredBorderPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_IGNORED_BORDER_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_BORDER_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_BORDER_PROMISE)
        }
    }

    private fun addMilitaryThreatWeights(threat: AiThreat, w: IntArray, rules: AiRuleset) {
        when (threat) {
            AiThreat.Critical -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_CRITICAL)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_CRITICAL)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_CRITICAL)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_CRITICAL)
            }
            AiThreat.Severe -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_SEVERE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_SEVERE)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_SEVERE)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_SEVERE)
            }
            AiThreat.Major -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_MAJOR)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_MAJOR)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_MAJOR)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_MAJOR)
            }
            AiThreat.Minor -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_MINOR)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_MINOR)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_MINOR)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_MINOR)
            }
            AiThreat.None -> w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_MILITARY_THREAT_NONE)
        }
    }

    /** Nukes: who has them and how likely they are to use them (the original's untuned constants) */
    private fun addNukeWeights(civ: Civilization, other: Civilization, w: IntArray) {
        val theirNukes = other.units.getCivUnits().count { it.isNuclearWeapon() }
        val ourNukes = civ.units.getCivUnits().count { it.isNuclearWeapon() }
        if (theirNukes == 0 && ourNukes == 0) return
        val likelihood = when {
            other.isHuman() -> 100   // assume humans will use them if they have them
            civ.ai.diplomacy.memoryOf(other).numTimesNuked > 0 || other.ai.diplomacy.memoryOf(civ).numTimesNuked > 0 -> 100
            else -> {
                val flavor = other.ai.personalityAndGrandStrategy(Flavor.UseNuke) + 1
                flavor * flavor    // using nukes has to pass two rolls
            }
        }
        if (theirNukes == 0 && ourNukes > 0) {
            w[WAR] += 25; w[HOSTILE] += 25; w[DECEPTIVE] += 25; w[GUARDED] -= 25; w[AFRAID] -= 50
        } else if (theirNukes > 0 && ourNukes == 0 && likelihood > 50) {
            w[AFRAID] += 50; w[GUARDED] += 50; w[DECEPTIVE] -= 25; w[WAR] -= 50
        } else if (theirNukes > 0 && ourNukes > 0 && likelihood > 25) {
            w[GUARDED] += 25; w[DECEPTIVE] -= 25; w[WAR] -= 25; w[NEUTRAL] += 25
        }
    }

    /** CvPlayerTrade::GetAllTradeValueFromPlayerTimes100(YIELD_GOLD) / 100: gold we earn from routes between us and them */
    fun tradeGoldFrom(civ: Civilization, other: Civilization): Int {
        var gold = 0f
        for (route in civ.tradeRoutes.routes) if (route.getDestinationCiv() == other) gold += route.originYields.gold
        for (route in other.tradeRoutes.routes) if (route.getDestinationCiv() == civ) gold += route.destinationYields.gold
        return gold.toInt()
    }
}
