package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.AggressivePosture
import com.unciv.logic.automation.diplomacy.DiploStatement
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.MajorCivMemory
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  The informational statements of CvDiplomacyAI::DoContactPlayer. In a turn the AI says at most one thing to a player and the first
 *  statement that applies wins, in the original's order: coop war, demand, (aggressive military warning), then these, then friendship,
 *  denouncements and every offer. So a statement here delays all of those by a turn, whoever receives it: for an AI receiver
 *  DoSendStatementToPlayer does nothing else (only the log and the slot), for a human it shows a message.
 *
 *  Where Unciv shows the human something already, the statement only takes the slot: a notification of a killed spy, the alerts a protector
 *  gets when a city-state it protects is attacked or bullied, the alert that tells a promise breaker that it was noticed. Where the
 *  statement asks the human for a promise, Unciv's request alert is that message ([Demand]); the remaining messages are notifications.
 *
 *  Not ported: DoKilledCityStateStatement (needs the promise not to attack city-states, which only the original's diplomacy screen offers a
 *  human and no AI ever makes) and the "serious" expansion and plot buying warnings (commented out of DoContactPlayer).
 */
object InformationalStatementAi {

    /** The statement to send, the city-state or promise it is about if any */
    class Pending(val statement: DiploStatement, val cityState: Civilization? = null)

    /** Sends the first informational statement that applies to [other], if any: true when the slot of this turn is used */
    fun contact(civ: Civilization, other: Civilization): Boolean {
        val pending = pick(civ, other, civ.gameInfo.ruleset.ai) ?: return false
        DiplomacyAutomation.sendStatement(civ, other, pending.statement)
        if (other.isHuman()) tellHuman(civ, other, pending)
        return true
    }

    /** The statements in the order of DoContactPlayer, each as its DoXStatement function decides it */
    fun pick(civ: Civilization, other: Civilization, rules: AiRuleset): Pending? {
        val memory = civ.ai.diplomacy.memoryOf(other)
        val turn = civ.gameInfo.turns
        fun since(statement: DiploStatement) = DiplomacyAutomation.turnsSinceStatement(civ, memory, statement)
        fun pending(statement: DiploStatement) = Pending(statement)

        // DoAttackedCityStateStatement: they attacked a city-state we protect since our last turn
        protectedMinor(civ, memory.protectedMinorAttacked)?.let { minor ->
            if (memory.protectedMinorAttackedUnseen && since(DiploStatement.AttackedProtectedCityState) >= 1)
                return Pending(DiploStatement.AttackedProtectedCityState, minor)
        }
        // DoBulliedCityStateStatement: they bullied one this turn (no promise about it can be made, so no promise is broken or ignored)
        protectedMinor(civ, memory.protectedMinorBullied)?.let { minor ->
            if (memory.protectedMinorBulliedTurn == turn && !memory.brokenBullyCityStatePromise && !memory.ignoredBullyCityStatePromise &&
                since(DiploStatement.BulliedProtectedCityState) >= 1) return Pending(DiploStatement.BulliedProtectedCityState, minor)
        }
        if (expansionWarning(civ, other, memory, rules)) return pending(DiploStatement.ExpansionWarning)
        if (memory.brokenExpansionPromiseValue > 0 && since(DiploStatement.ExpansionBrokenPromise) >= NEVER)
            return pending(DiploStatement.ExpansionBrokenPromise)
        if (plotBuyingWarning(civ, other, memory, rules)) return pending(DiploStatement.PlotBuyingWarning)
        if (memory.brokenBorderPromiseValue > 0 && since(DiploStatement.PlotBuyingBrokenPromise) >= NEVER)
            return pending(DiploStatement.PlotBuyingBrokenPromise)

        // DoWeAttackedYourMinor, DoWeBulliedYourMinor: the last city-state of theirs we hurt wins
        var attacked: Civilization? = null
        var bullied: Civilization? = null
        for (minor in civ.gameInfo.getAliveCityStates()) {
            if (other !in minor.cityStateFunctions.getProtectorCivs()) continue
            val minorMemory = civ.ai.diplomacy.memoryOf(minor)
            if (civ.isAtWarWith(minor) && minorMemory.theirWarValueLostToUs > minorMemory.lastRoundTheirWarValueLostToUs &&
                minor.civID !in memory.sentAttackProtectedMinorTaunts && since(DiploStatement.WeAttackedYourMinor) >= 1) attacked = minor
            if (turn > 0 && minorMemory.turnLastBulliedByUs == turn - 1 && since(DiploStatement.WeBulliedYourMinor) >= 1) bullied = minor
        }
        attacked?.let { return Pending(DiploStatement.WeAttackedYourMinor, it) }
        bullied?.let { return Pending(DiploStatement.WeBulliedYourMinor, it) }

        // DoKilledYourSpy, DoKilledMySpy, DoCaughtYourSpy
        if (memory.numSpiesKilled > 0 && memory.turnLastSpyKilled == turn && since(DiploStatement.KilledYourSpy) >= 1)
            return pending(DiploStatement.KilledYourSpy)
        if (turn > 0 && memory.turnLastSpyDied == turn - 1 && canAskPromise(civ, other, Demand.DontSpyOnUs, memory.brokenSpyPromise, memory.ignoredSpyPromise) &&
            since(DiploStatement.KilledMySpy) >= 1) return pending(DiploStatement.KilledMySpy)
        if (memory.turnLastSpyCaught == turn && canAskPromise(civ, other, Demand.DontSpyOnUs, memory.brokenSpyPromise, memory.ignoredSpyPromise) &&
            since(DiploStatement.CaughtYourSpy) >= 1) return pending(DiploStatement.CaughtYourSpy)

        leagueStatement(civ, other, memory, rules)?.let { return pending(it) }

        // DoConvertedMyCityStatement, DoDugUpMyYardStatement
        if (memory.negativeReligiousConversionPoints >= rules.define(AiDefine.RELIGION_DIPLO_HIT_THRESHOLD) &&
            canAskPromise(civ, other, Demand.DoNotSpreadReligion, memory.brokenNoConvertPromise, memory.ignoredNoConvertPromise) &&
            since(DiploStatement.StopConversions) >= 1) return pending(DiploStatement.StopConversions)
        if ((civ.getDiplomacyManager(other)?.negativeArchaeologyPoints ?: 0) >= DIG_POINTS_THRESHOLD &&
            canAskPromise(civ, other, Demand.DoNotDigInOurLands, memory.brokenNoDiggingPromise, memory.ignoredNoDiggingPromise) &&
            since(DiploStatement.StopDigging) >= 1) return pending(DiploStatement.StopDigging)
        return null
    }

    /** MAX_TURNS_SAFE_ESTIMATE as a statement interval: the statement is sent once, only while the log has no earlier one */
    private const val NEVER = 9999

    /** DoDugUpMyYardStatement: the points one dig adds, from which the statement is made */
    private const val DIG_POINTS_THRESHOLD = 10

    /** GetOtherPlayerProtectedMinorAttacked / Bullied: the city-state [id], if it is still valid for us and we still protect it */
    private fun protectedMinor(civ: Civilization, id: String?): Civilization? {
        val minor = id?.let { civ.gameInfo.getCivilizationOrNull(it) } ?: return null
        if (!minor.isCityState || !WarStateAi.isPlayerValid(civ, minor) || civ !in minor.cityStateFunctions.getProtectorCivs()) return null
        return minor
    }

    /**
     *  Whether the original would ask [other] for a promise [demand] stands for: no promise broken, ignored or made. A promise made and then
     *  broken is no statement either: the original only marks it broken, which Unciv's violation flow does.
     */
    private fun canAskPromise(civ: Civilization, other: Civilization, demand: Demand, broken: Boolean, ignored: Boolean): Boolean {
        if (broken || ignored) return false
        val diplomacy = civ.getDiplomacyManager(other) ?: return false
        return !diplomacy.hasFlag(demand.agreedToDemand) && !diplomacy.hasFlag(demand.willIgnoreViolation)
    }

    /**
     *  DoExpansionWarningStatement: they founded a city lately (EXPANSION_BICKER_TIMEOUT) and never promised not to expand, and we are
     *  fiercely opposed to their expansion or have a strong land dispute with them that was weaker last turn while their expansion posture
     *  is at least medium; not again within EXPANSION_PROMISE_TURNS_EFFECTIVE turns (scaled by the game speed).
     */
    private fun expansionWarning(civ: Civilization, other: Civilization, memory: MajorCivMemory, rules: AiRuleset): Boolean {
        val settled = other.ai.diplomacy.turnsSinceSettledLastCity
        if (settled < 0 || settled >= rules.define(AiDefine.EXPANSION_BICKER_TIMEOUT)) return false
        if (memory.expansionPromiseTurn >= 0 || memory.ignoredExpansionPromiseValue > 0 || memory.brokenExpansionPromiseValue > 0) return false
        if (!canAskPromise(civ, other, Demand.DoNotSettleNearUs, broken = false, ignored = false)) return false
        val send = memory.landDispute >= DisputeLevel.Fierce ||
            memory.landDispute >= DisputeLevel.Strong && memory.lastTurnLandDispute < DisputeLevel.Strong &&
            memory.expansionPosture >= AggressivePosture.Medium
        val interval = rules.define(AiDefine.EXPANSION_PROMISE_TURNS_EFFECTIVE) * DiplomacyTurn.opinionDurationPercent(civ) / 100
        return send && DiplomacyAutomation.turnsSinceStatement(civ, memory, DiploStatement.ExpansionWarning) >= interval
    }

    /**
     *  DoPlotBuyingWarningStatement: they never promised about borders and we have a strong land dispute with them while they buy plots
     *  (posture at least low); not again within BORDER_PROMISE_TURNS_EFFECTIVE turns (scaled by the game speed).
     */
    private fun plotBuyingWarning(civ: Civilization, other: Civilization, memory: MajorCivMemory, rules: AiRuleset): Boolean {
        if (memory.borderPromiseTurn >= 0 || memory.ignoredBorderPromiseValue > 0 || memory.brokenBorderPromiseValue > 0) return false
        if (!canAskPromise(civ, other, Demand.DoNotBuyLandNearUs, broken = false, ignored = false)) return false
        if (memory.landDispute < DisputeLevel.Strong || memory.plotBuyingPosture < AggressivePosture.Low) return false
        val interval = rules.define(AiDefine.BORDER_PROMISE_TURNS_EFFECTIVE) * DiplomacyTurn.opinionDurationPercent(civ) / 100
        return DiplomacyAutomation.turnsSinceStatement(civ, memory, DiploStatement.PlotBuyingWarning) >= interval
    }

    /**
     *  DoTheySupportedOurHosting, DoWeLikedTheirProposal, DoWeDislikedTheirProposal, DoTheySupportedOurProposal, DoTheyFoiledOurProposal:
     *  a World Congress memory that still moves our opinion of them, unless it does not suit how we feel about them; the two we say
     *  of their proposals only when our chattiness beats a roll of 0..9. Each is said again only once the opinion effect has run its course.
     */
    private fun leagueStatement(civ: Civilization, other: Civilization, memory: MajorCivMemory, rules: AiRuleset): DiploStatement? {
        val league = civ.gameInfo.league?.members?.get(civ.civID)?.alignmentMemory?.get(other.civID) ?: return null
        val ai = civ.ai.diplomacy
        val turn = civ.gameInfo.turns
        val hostileApproach = ai.approachTowards(other, hideTrueFeelings = true).let { it == MajorCivApproach.War || it == MajorCivApproach.Hostile }
        val opinion = ai.opinionOf(other)
        val chattiness = civ.ai.diplomacyValue(DiplomacyPersonality.Chattiness)
        fun since(statement: DiploStatement) = DiplomacyAutomation.turnsSinceStatement(civ, memory, statement)
        fun fits(positive: Boolean) = if (positive) opinion > MajorCivOpinion.Competitor && !hostileApproach
            else opinion < MajorCivOpinion.Favorable && ai.approachTowards(other, hideTrueFeelings = true) != MajorCivApproach.Friendly

        if (OpinionAi.supportedMyHostingScore(league, turn, rules) != 0 && fits(true) &&
            since(DiploStatement.TheySupportedOurHosting) >= rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING_NUM_TURNS))
            return DiploStatement.TheySupportedOurHosting
        if (OpinionAi.likedTheirProposalScore(league, turn, rules) != 0 && fits(true) &&
            chattiness > civ.ai.random("DiplomacyAI.WeLikedTheirProposal" + other.civID).nextInt(10) &&
            since(DiploStatement.WeLikedTheirProposal) >= rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_NUM_TURNS))
            return DiploStatement.WeLikedTheirProposal
        if (OpinionAi.dislikedTheirProposalScore(league, turn, rules) != 0 && fits(false) &&
            chattiness > civ.ai.random("DiplomacyAI.WeDislikedTheirProposal" + other.civID).nextInt(10) &&
            since(DiploStatement.WeDislikedTheirProposal) >= rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_NUM_TURNS))
            return DiploStatement.WeDislikedTheirProposal
        if (OpinionAi.supportedMyProposalScore(league, turn, rules) != 0 && fits(true) &&
            since(DiploStatement.TheySupportedOurProposal) >= rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL_NUM_TURNS))
            return DiploStatement.TheySupportedOurProposal
        if (OpinionAi.foiledMyProposalScore(league, turn, rules) != 0 && fits(false) &&
            since(DiploStatement.TheyFoiledOurProposal) >= rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL_NUM_TURNS))
            return DiploStatement.TheyFoiledOurProposal
        return null
    }

    //region What a human sees

    private fun tellHuman(civ: Civilization, human: Civilization, pending: Pending) {
        val minor = pending.cityState
        when (pending.statement) {
            DiploStatement.ExpansionWarning -> askForPromise(civ, human, Demand.DoNotSettleNearUs)
            DiploStatement.PlotBuyingWarning -> askForPromise(civ, human, Demand.DoNotBuyLandNearUs)
            DiploStatement.KilledMySpy, DiploStatement.CaughtYourSpy -> askForPromise(civ, human, Demand.DontSpyOnUs)
            DiploStatement.StopConversions -> askForPromise(civ, human, Demand.DoNotSpreadReligion)
            DiploStatement.StopDigging -> askForPromise(civ, human, Demand.DoNotDigInOurLands)
            DiploStatement.AttackedProtectedCityState -> notify(civ, human, "[${civ.civName}] is angry that you attacked [${minor!!.civName}], a city-state under their protection.")
            DiploStatement.BulliedProtectedCityState -> notify(civ, human, "[${civ.civName}] is angry that you bullied [${minor!!.civName}], a city-state under their protection.")
            DiploStatement.WeAttackedYourMinor ->
                // Unciv's alert to the protector of an attacked city-state is the message; we say it once for this war
                civ.ai.diplomacy.memoryOf(human).sentAttackProtectedMinorTaunts.add(minor!!.civID)
            DiploStatement.TheySupportedOurHosting -> notify(civ, human, "[${civ.civName}] thanks you for supporting them as host of the World Congress.")
            DiploStatement.WeLikedTheirProposal -> notify(civ, human, "[${civ.civName}] liked your proposal in the World Congress.")
            DiploStatement.WeDislikedTheirProposal -> notify(civ, human, "[${civ.civName}] disliked your proposal in the World Congress.")
            DiploStatement.TheySupportedOurProposal -> notify(civ, human, "[${civ.civName}] thanks you for supporting their proposal in the World Congress.")
            DiploStatement.TheyFoiledOurProposal -> notify(civ, human, "[${civ.civName}] is displeased that you foiled their proposal in the World Congress.")
            else -> {}  // a message Unciv already shows: see the class comment
        }
    }

    /** DoSendStatementToPlayer asks the human for the promise: Unciv's request alert, once at a time */
    private fun askForPromise(civ: Civilization, human: Civilization, demand: Demand) {
        if (human.popupAlerts.none { it.type == demand.demandAlert && it.value == civ.civID })
            human.popupAlerts.add(PopupAlert(demand.demandAlert, civ.civID))
    }

    private fun notify(civ: Civilization, human: Civilization, text: String) =
        human.addNotification(text, NotificationCategory.Diplomacy, civ.civName)
    //endregion

    /** CvDiplomacyAI::CacheOtherPlayerWarValuesLost and the end of the turn counters: what the statements of the next turn compare with */
    fun endOfTurn(civ: Civilization) {
        for (memory in civ.ai.diplomacy.memories.values) {
            memory.lastRoundTheirWarValueLostToUs = memory.theirWarValueLostToUs
            memory.protectedMinorAttackedUnseen = false
        }
    }
}
