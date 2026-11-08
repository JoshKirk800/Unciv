package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Cooperative wars of CvDiplomacyAI: asking a partner to join a war (DoCoopWarStatement, DoTestCoopWarDesire, GetCoopWarScore), the
 *  partner's answer (GetWillingToAgreeToCoopWarState), the "soon" counter that triggers the war 10 turns later (MakeWar) and the lock-in
 *  (IsLockedIntoCoopWar). The state is kept by the partner: `civ.ai.diplomacy.memoryOf(partner).coopWars[target]`.
 */
object CoopWarAi {

    fun state(civ: Civilization, partner: Civilization, target: Civilization): CoopWarState? =
        civ.ai.diplomacy.memories[partner.civID]?.coopWars?.get(target.civID)?.state

    fun counter(civ: Civilization, partner: Civilization, target: Civilization): Int =
        civ.ai.diplomacy.memories[partner.civID]?.coopWars?.get(target.civID)?.counter ?: -1

    fun entry(civ: Civilization, partner: Civilization, target: Civilization): CoopWarEntry =
        civ.ai.diplomacy.memoryOf(partner).coopWars.getOrPut(target.civID) { CoopWarEntry() }

    /** GetGlobalCoopWarAcceptedState: the furthest any partner has agreed to go against [target] */
    private fun globalState(civ: Civilization, target: Civilization): CoopWarState? {
        var best: CoopWarState? = null
        for ((_, memory) in civ.ai.diplomacy.memories) {
            val state = memory.coopWars[target.civID]?.state ?: continue
            if (best == null || state > best) best = state
        }
        return best
    }

    /** GetGlobalCoopWarCounter: the most recent agreement against [target] */
    private fun globalCounter(civ: Civilization, target: Civilization): Int {
        var best = Int.MAX_VALUE
        for ((_, memory) in civ.ai.diplomacy.memories) {
            val counter = memory.coopWars[target.civID]?.counter ?: continue
            if (counter in 0 until best) best = counter
        }
        return best
    }

    /** CvDiplomacyAI::IsLockedIntoCoopWar: agreed (now or soon) with someone against [target] within COOP_WAR_LOCKED_TURNS */
    fun isLockedIntoCoopWar(civ: Civilization, target: Civilization, rules: AiRuleset): Boolean {
        val state = globalState(civ, target)
        if (state != CoopWarState.Accepted && state != CoopWarState.Soon) return false
        return globalCounter(civ, target) <= rules.define(AiDefine.COOP_WAR_LOCKED_TURNS)
    }

    /** CvDiplomacyAI::GetCoopWarScore: how much [civ] wants a war with [partner] against [target] */
    fun coopWarScore(civ: Civilization, partner: Civilization, target: Civilization, askedByPlayer: Boolean, rules: AiRuleset): Int {
        val ai = civ.ai.diplomacy
        val approachToPartner = ai.approachTowards(partner)
        val opinionOfPartner = ai.opinionOf(partner)
        val approachToTarget = ai.approachTowards(target)
        val opinionOfTarget = ai.opinionOf(target)
        if (civ.getDiplomacyManager(target)?.canDeclareWar() != true) return 0
        if (partner.getDiplomacyManager(target)?.canDeclareWar() != true) return 0
        // The one asking must be planning war already
        if (!askedByPlayer && approachToTarget != MajorCivApproach.War && approachToTarget != MajorCivApproach.Deceptive) return 0
        if (approachToPartner == MajorCivApproach.War || approachToPartner == MajorCivApproach.Hostile) return 0
        if (opinionOfPartner == MajorCivOpinion.Unforgivable) return 0
        if (!WarStateAi.isPlayerValid(civ, target)) return 0
        if (OpinionAi.isDoFAccepted(civ, target)) return 0

        var weight = 0
        if (askedByPlayer && (approachToPartner == MajorCivApproach.Friendly || approachToPartner == MajorCivApproach.Deceptive ||
                opinionOfPartner >= MajorCivOpinion.Favorable)) weight += 2
        if (approachToTarget == MajorCivApproach.War) weight += 5
        if (approachToTarget == MajorCivApproach.Hostile) weight += 2
        if (civ.ai.isGoingForWorldConquest()) {
            weight += 3
            if (approachToTarget == MajorCivApproach.Deceptive) weight += 2
        } else if (civ.ai.isGoingForDiploVictory()) weight -= 2
        weight += when (opinionOfTarget) {
            MajorCivOpinion.Unforgivable -> 12
            MajorCivOpinion.Enemy -> 8
            MajorCivOpinion.Competitor -> 4
            MajorCivOpinion.Favorable -> -1
            MajorCivOpinion.Friend -> -5
            MajorCivOpinion.Ally -> -10
            MajorCivOpinion.Neutral -> 0
        }
        // Gold from trade with the partner (the original's code reads the partner here)
        val tradeGold = ApproachAi.tradeGoldFrom(civ, partner)
        if (tradeGold > 0) {
            weight -= 2
            val goldRate = civ.stats.statsForNextTurn.gold.toInt()
            if (goldRate >= 0 && goldRate - tradeGold < 0) weight -= 5
        }
        if (WarStateAi.isPlayerRecklessExpander(civ, target)) weight += 4
        weight += when (ai.warmongerThreat(target)) {
            AiThreat.Minor -> 1
            AiThreat.Major -> 3
            AiThreat.Severe -> 5
            AiThreat.Critical -> 7
            AiThreat.None -> 0
        }
        if (weight > 0 && OpinionAi.isDoFAccepted(civ, partner)) weight += 5
        weight = weight * when (WarStateAi.targetValue(civ, target)) {
            TargetValue.Impossible -> 66
            TargetValue.Bad -> 75
            TargetValue.Average -> 100
            TargetValue.Favorable -> 110
            TargetValue.Soft -> 120
            null -> 100
        } / 100
        weight += civ.ai.random("DiplomacyAI.CoopWarScore" + partner.civID + target.civID).nextInt(5)
        return if (weight >= 15) weight else 0
    }

    /** CvDiplomacyAI::DoTestCoopWarDesire: the best target to ask [partner] to fight with us, null if none */
    fun coopWarTarget(civ: Civilization, partner: Civilization, rules: AiRuleset): Civilization? {
        val ai = civ.ai.diplomacy
        val approach = ai.approachTowards(partner)
        if (approach == MajorCivApproach.War || approach == MajorCivApproach.Hostile) return null
        if (ai.opinionOf(partner) == MajorCivOpinion.Unforgivable) return null
        var best: Civilization? = null
        var bestScore = 0
        for (target in civ.gameInfo.civilizations) {
            if (!target.isMajorCiv || target == partner || !WarStateAi.isPlayerValid(civ, target)) continue
            if (state(civ, partner, target) != null) continue
            // 30 turn buffer after a rejection
            val counter = counter(civ, partner, target)
            if (counter in 0 until 30) continue
            val score = coopWarScore(civ, partner, target, askedByPlayer = false, rules)
            if (score > bestScore) {
                bestScore = score
                best = target
            }
        }
        return best
    }

    /** CvDiplomacyAI::GetWillingToAgreeToCoopWarState: [civ] was asked by [asker] to fight [target] */
    fun willingToAgree(civ: Civilization, asker: Civilization, target: Civilization, rules: AiRuleset): CoopWarState {
        if (coopWarScore(civ, asker, target, askedByPlayer = true, rules) >= 15) {
            val value = WarStateAi.targetValue(civ, target)
            return if (value != null && value >= TargetValue.Favorable) CoopWarState.Accepted else CoopWarState.Soon
        }
        return CoopWarState.Rejected
    }

    /**
     *  DoSendStatementToPlayer(COOP_WAR_REQUEST) with an AI partner: [civ] asks [partner] to fight [target] with it. Accepted: both declare war
     *  now and are locked in for COOP_WAR_LOCKED_LENGTH turns; Soon: war in COOP_WAR_SOON_COUNTER turns (see [doCoopWarCounters]).
     */
    fun requestCoopWar(civ: Civilization, partner: Civilization, target: Civilization, rules: AiRuleset) {
        entry(civ, partner, target).counter = 0
        val partnerEntry = entry(partner, civ, target)
        partnerEntry.counter = 0
        val accepted = willingToAgree(partner, civ, target, rules)
        partnerEntry.state = accepted
        if (accepted == CoopWarState.Accepted) {
            WarAi.declareWar(civ, target)
            WarAi.declareWar(partner, target)
            val locked = rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
            civ.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
            partner.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += locked
        }
        if (accepted != CoopWarState.Rejected) entry(civ, partner, target).state = accepted
    }

    /** The coop war part of CvDiplomacyAI::MakeWar: counters advance; AI partners who agreed to "soon" go to war at COOP_WAR_SOON_COUNTER */
    fun doCoopWarCounters(civ: Civilization, rules: AiRuleset) {
        for ((partnerId, memory) in civ.ai.diplomacy.memories) {
            val partner = civ.gameInfo.getCivilizationOrNull(partnerId) ?: continue
            if (!partner.isMajorCiv || !WarStateAi.isPlayerValid(civ, partner)) continue
            for ((targetId, entry) in memory.coopWars) {
                if (entry.counter < 0) continue
                val target = civ.gameInfo.getCivilizationOrNull(targetId) ?: continue
                if (!WarStateAi.isPlayerValid(civ, target)) continue
                entry.counter++
                if (!partner.isHuman() && entry.state == CoopWarState.Soon && entry.counter == rules.define(AiDefine.COOP_WAR_SOON_COUNTER)) {
                    entry.state = CoopWarState.Accepted
                    entry.counter = 0
                    WarAi.declareWar(civ, target)
                    civ.ai.diplomacy.memoryOf(target).turnsLockedIntoWar += rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
                }
            }
        }
    }
}
