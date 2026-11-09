package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import kotlin.math.max

/**
 *  The promises of CvDiplomacyAI (requests one player makes of another and what breaking or ignoring them does), on Unciv's [Demand]s:
 *  DoNotSettleNearUs = the expansion promise, DoNotBuyLandNearUs = the border (plot buying) promise, DontSpyOnUs = the spy promise,
 *  DoNotSpreadReligion = the no-convert promise, DoNotDigInOurLands = the no-digging promise, DoNotAttackUs = the military promise.
 *  Unciv has no city-state attack or bully promise requests; their memories exist and decay as the original's but nothing sets them.
 *  A promise is the [Demand.agreedToDemand] flag the AI holds towards the promiser; the expansion, border and military promises last as
 *  long as the original's ([promiseLength]), the expansion and border promises break when the promiser's posture towards the AI rises
 *  ([testPromises]), the others on the event that breaks them (see NextTurnAutomation.onDemandViolation).
 *  Also the acceptance tests an AI uses when asked (IsDontSettleAcceptable, IsStopSpyingAcceptable, IsStopSpreadingReligionAcceptable,
 *  IsStopDiggingAcceptable).
 */
object PromiseAi {

    /** CvDiplomacyAI::DoTestPromises: the military promise ends after 20 turns (the promise counter limit in the original) */
    private const val MILITARY_PROMISE_TURNS = 20

    /**
     *  How many turns the promise [promiser] makes to [promisee] by agreeing to [demand] lasts, null where Unciv's own length (100 turns) applies.
     *  A human's promise to an AI: the expansion promise EXPANSION_PROMISE_TURNS_EFFECTIVE and the border promise BORDER_PROMISE_TURNS_EFFECTIVE
     *  turns scaled by the game speed's OpinionDurationPercent (IsPlayerMadeExpansionPromise / IsPlayerMadeBorderPromise), the military promise
     *  20 turns. An AI's promise to a human: don't settle near them IC_MEMORY_TURN_EXPIRATION turns (SetPlayerNoSettleRequestAccepted), stop
     *  spying STOP_SPYING_MEMORY_TURN_EXPIRATION turns (SetPlayerStopSpyingRequestAccepted). The original's spy, no-convert and no-digging
     *  promises have no length: they last until broken or ended by war; Unciv's flag cannot, so they keep Unciv's length.
     */
    fun promiseLength(promiser: Civilization, promisee: Civilization, demand: Demand): Int? {
        val rules = promisee.gameInfo.ruleset.ai
        if (promiser.isHuman()) {
            val percent = DiplomacyTurn.opinionDurationPercent(promisee)
            return when (demand) {
                Demand.DoNotSettleNearUs -> rules.define(AiDefine.EXPANSION_PROMISE_TURNS_EFFECTIVE) * percent / 100
                Demand.DoNotBuyLandNearUs -> rules.define(AiDefine.BORDER_PROMISE_TURNS_EFFECTIVE) * percent / 100
                Demand.DoNotAttackUs -> MILITARY_PROMISE_TURNS
                else -> null
            }
        }
        return when (demand) {
            Demand.DoNotSettleNearUs -> rules.define(AiDefine.IC_MEMORY_TURN_EXPIRATION)
            Demand.DontSpyOnUs -> rules.define(AiDefine.STOP_SPYING_MEMORY_TURN_EXPIRATION)
            else -> null
        }
    }

    /**
     *  DoCounters' decays of the promise values. When a penalty has run out the promise may be asked again: the expansion promise is forgotten,
     *  the border promise is not (the original stamps the border promise with the current turn when it resets it, so it was "ever made").
     */
    fun decayPromiseValues(memory: MajorCivMemory, rules: AiRuleset, turn: Int) {
        val brokenExpansion = memory.brokenExpansionPromiseValue
        val ignoredExpansion = memory.ignoredExpansionPromiseValue
        memory.brokenExpansionPromiseValue = max(0, brokenExpansion - rules.define(AiDefine.EXPANSION_PROMISE_BROKEN_PER_TURN_DECAY))
        memory.ignoredExpansionPromiseValue = max(0, ignoredExpansion - rules.define(AiDefine.EXPANSION_PROMISE_IGNORED_PER_TURN_DECAY))
        if (penaltiesRanOut(brokenExpansion, ignoredExpansion, memory.brokenExpansionPromiseValue, memory.ignoredExpansionPromiseValue)) {
            memory.expansionPromiseTurn = -1
            memory.expansionPromisePosture = null
        }
        val brokenBorder = memory.brokenBorderPromiseValue
        val ignoredBorder = memory.ignoredBorderPromiseValue
        memory.brokenBorderPromiseValue = max(0, brokenBorder - rules.define(AiDefine.BORDER_PROMISE_BROKEN_PER_TURN_DECAY))
        memory.ignoredBorderPromiseValue = max(0, ignoredBorder - rules.define(AiDefine.BORDER_PROMISE_IGNORED_PER_TURN_DECAY))
        if (penaltiesRanOut(brokenBorder, ignoredBorder, memory.brokenBorderPromiseValue, memory.ignoredBorderPromiseValue)) {
            memory.borderPromiseTurn = turn
            memory.borderPromisePosture = null
        }
    }

    private fun penaltiesRanOut(brokenBefore: Int, ignoredBefore: Int, broken: Int, ignored: Int) =
        (brokenBefore != 0 && broken == 0 && ignored == 0) || (ignoredBefore != 0 && ignored == 0 && broken == 0)

    /**
     *  CvDiplomacyAI::DoTestPromises. The military promise ends with its flag. For an AI, a promise a human made not to expand towards it or
     *  not to buy land near it is broken when the human's expansion / plot buying posture has risen above what it was at the promise;
     *  the break is raised as the demand's violation (NextTurnAutomation.onDemandViolation then tells the human and notes the broken promise).
     *  A promise that ran out tells the human so.
     */
    fun testPromises(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            if (!other.isMajorCiv) continue
            val memory = civ.ai.diplomacy.memoryOf(other)
            if (memory.madeMilitaryPromise && civ.getDiplomacyManager(other)?.hasFlag(Demand.DoNotAttackUs.agreedToDemand) != true)
                memory.madeMilitaryPromise = false
            // The shadow AI of a human does not judge promises
            if (civ.isHuman() || !other.isHuman()) continue
            val diplomacy = civ.getDiplomacyManager(other) ?: continue
            if (testPosturePromise(civ, other, Demand.DoNotSettleNearUs, memory.expansionPromisePosture, memory.expansionPosture, memory.brokenExpansionPromiseValue))
                memory.expansionPromisePosture = null
            else if (memory.expansionPromisePosture != null && !diplomacy.hasFlag(Demand.DoNotSettleNearUs.agreedToDemand)) {
                memory.expansionPromisePosture = null
                other.addNotification("Your promise to [${civ.civName}] not to settle near them has expired.", NotificationCategory.Diplomacy, civ.civName)
            }
            if (testPosturePromise(civ, other, Demand.DoNotBuyLandNearUs, memory.borderPromisePosture, memory.plotBuyingPosture, memory.brokenBorderPromiseValue))
                memory.borderPromisePosture = null
            else if (memory.borderPromisePosture != null && !diplomacy.hasFlag(Demand.DoNotBuyLandNearUs.agreedToDemand)) {
                memory.borderPromisePosture = null
                other.addNotification("Your promise to [${civ.civName}] not to buy land near them has expired.", NotificationCategory.Diplomacy, civ.civName)
            }
        }
    }

    /** Whether [other]'s promise of [demand] to [civ] broke now: the promise stands, its posture at the promise is known and has been exceeded */
    private fun testPosturePromise(civ: Civilization, other: Civilization, demand: Demand, promised: AggressivePosture?,
                                   current: AggressivePosture, brokenValue: Int): Boolean {
        val diplomacy = civ.getDiplomacyManager(other) ?: return false
        if (promised == null || !diplomacy.hasFlag(demand.agreedToDemand) || current <= promised || brokenValue != 0) return false
        diplomacy.setFlag(demand.violationOccurred, 2)
        return true
    }

    fun brokenExpansionPromiseScore(memory: MajorCivMemory, opinionDurationPercent: Int, rules: AiRuleset): Int {
        if (memory.brokenExpansionPromiseValue <= 0) return 0
        return minOf(memory.brokenExpansionPromiseValue / (rules.define(AiDefine.BROKEN_EXPANSION_PROMISE_PER_OPINION_WEIGHT) * opinionDurationPercent),
            rules.define(AiDefine.OPINION_WEIGHT_EXPANSION_PROMISE_BROKE_MAX))
    }

    fun ignoredExpansionPromiseScore(memory: MajorCivMemory, opinionDurationPercent: Int, rules: AiRuleset): Int {
        if (memory.brokenExpansionPromiseValue > 0 || memory.ignoredExpansionPromiseValue <= 0) return 0
        return minOf(memory.ignoredExpansionPromiseValue / (rules.define(AiDefine.IGNORED_EXPANSION_PROMISE_PER_OPINION_WEIGHT) * opinionDurationPercent),
            rules.define(AiDefine.OPINION_WEIGHT_EXPANSION_PROMISE_IGNORED_MAX))
    }

    /** GetBrokenBorderPromiseScore: above the cap the original adds the cap to the value instead of capping (kept) */
    fun brokenBorderPromiseScore(memory: MajorCivMemory, opinionDurationPercent: Int, rules: AiRuleset): Int {
        if (memory.brokenBorderPromiseValue <= 0) return 0
        var weight = memory.brokenBorderPromiseValue / (rules.define(AiDefine.BROKEN_BORDER_PROMISE_PER_OPINION_WEIGHT) * opinionDurationPercent)
        if (weight > rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_BROKE_MAX)) weight += rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_BROKE_MAX)
        return weight
    }

    fun ignoredBorderPromiseScore(memory: MajorCivMemory, opinionDurationPercent: Int, rules: AiRuleset): Int {
        if (memory.brokenBorderPromiseValue > 0 || memory.ignoredBorderPromiseValue <= 0) return 0
        return minOf(memory.ignoredBorderPromiseValue / (rules.define(AiDefine.IGNORED_BORDER_PROMISE_PER_OPINION_WEIGHT) * opinionDurationPercent),
            rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_IGNORED_MAX))
    }

    //region Demands (Unciv's request flow) -> the original's promise memories

    /** [recipient] was asked [demand] by [demander]: GetNoSetterRequestScore / GetStopSpyingRequestScore of the recipient */
    fun onDemandAsked(demander: Civilization, recipient: Civilization, demand: Demand) {
        val memory = recipient.ai.diplomacy.memoryOf(demander)
        when (demand) {
            Demand.DoNotSettleNearUs -> memory.noSettleRequestEverAsked = true
            Demand.DontSpyOnUs -> memory.stopSpyingRequestEverAsked = true
            else -> {}
        }
    }

    /**
     *  [recipient] agreed: DoFromUIDiploEvent's promise accepted. A human's expansion or border promise to an AI notes the posture it
     *  must not exceed (SetPlayerExpansionPromiseData / SetPlayerBorderPromiseData) and the turn (SetPlayerMadeExpansionPromise); the
     *  military promise starts its counter.
     */
    fun onDemandAgreed(demander: Civilization, recipient: Civilization, demand: Demand) {
        onDemandAsked(demander, recipient, demand)
        val memory = demander.ai.diplomacy.memoryOf(recipient)
        if (demand == Demand.DoNotAttackUs) memory.madeMilitaryPromise = true
        if (demander.isHuman()) return
        val turn = demander.gameInfo.turns
        when (demand) {
            Demand.DoNotSettleNearUs -> {
                memory.expansionPromiseTurn = turn
                memory.expansionPromisePosture = memory.expansionPosture
            }
            Demand.DoNotBuyLandNearUs -> {
                memory.borderPromiseTurn = turn
                memory.borderPromisePosture = memory.plotBuyingPosture
            }
            else -> {}
        }
    }

    /** [recipient] refused: the demander remembers the ignored promise */
    fun onDemandRefused(demander: Civilization, recipient: Civilization, demand: Demand) {
        onDemandAsked(demander, recipient, demand)
        val memory = demander.ai.diplomacy.memoryOf(recipient)
        val rules = demander.gameInfo.ruleset.ai
        when (demand) {
            Demand.DoNotSettleNearUs -> memory.ignoredExpansionPromiseValue =
                rules.define(AiDefine.OPINION_WEIGHT_EXPANSION_PROMISE_IGNORED_MAX) * DiplomacyTurn.opinionDurationPercent(demander)
            Demand.DoNotBuyLandNearUs -> memory.ignoredBorderPromiseValue =
                rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_IGNORED_MAX) * DiplomacyTurn.opinionDurationPercent(demander)
            Demand.DontSpyOnUs -> memory.ignoredSpyPromise = true
            Demand.DoNotSpreadReligion -> memory.ignoredNoConvertPromise = true
            Demand.DoNotDigInOurLands -> memory.ignoredNoDiggingPromise = true
            Demand.DoNotAttackUs -> memory.ignoredMilitaryPromise = true
        }
    }

    /** [promiser] broke the promise it made to [promisee] */
    fun onDemandBroken(promisee: Civilization, promiser: Civilization, demand: Demand) {
        val memory = promisee.ai.diplomacy.memoryOf(promiser)
        val rules = promisee.gameInfo.ruleset.ai
        when (demand) {
            Demand.DoNotSettleNearUs -> if (memory.brokenExpansionPromiseValue == 0) memory.brokenExpansionPromiseValue =
                rules.define(AiDefine.OPINION_WEIGHT_EXPANSION_PROMISE_BROKE_MAX) * DiplomacyTurn.opinionDurationPercent(promisee)
            Demand.DoNotBuyLandNearUs -> if (memory.brokenBorderPromiseValue == 0) memory.brokenBorderPromiseValue =
                rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_BROKE_MAX) * DiplomacyTurn.opinionDurationPercent(promisee)
            Demand.DontSpyOnUs -> memory.brokenSpyPromise = true
            Demand.DoNotSpreadReligion -> memory.brokenNoConvertPromise = true
            Demand.DoNotDigInOurLands -> memory.brokenNoDiggingPromise = true
            Demand.DoNotAttackUs -> memory.brokenMilitaryPromise = true
        }
    }

    /**
     *  Whether the AI [civ] asks [offender], who did what [demand] is about and promised nothing yet, for the promise. The original's
     *  warnings only show to humans (DoSendStatementToPlayer does nothing for an AI receiver), so AIs are never asked:
     *  - DoNotSettleNearUs: CvDiplomacyAI::DoExpansionWarningStatement: a fierce land dispute, or a strong one that was weaker last turn
     *    while their expansion posture is at least medium, and they never promised before (Unciv raises the flag on the settling, so
     *    GetTurnsSinceSettledLastCity is 0);
     *  - DoNotBuyLandNearUs: DoPlotBuyingWarningStatement: a strong land dispute while they buy plots (posture at least low), never promised before;
     *  - DoNotSpreadReligion: DoConvertedMyCityStatement: their conversions reached RELIGION_DIPLO_HIT_THRESHOLD points;
     *  - DontSpyOnUs, DoNotDigInOurLands: DoCaughtYourSpyStatement / DoDugUpMyLandStatement, on every occurrence;
     *  - DoNotAttackUs: DoAggressiveMilitaryStatement, already tested when the flag was raised (see DiplomacyAutomation.checkMilitaryPresenceNearBorder).
     */
    fun wantsPromiseFrom(civ: Civilization, offender: Civilization, demand: Demand): Boolean {
        if (!offender.isHuman()) return false
        val memory = civ.ai.diplomacy.memoryOf(offender)
        return when (demand) {
            Demand.DoNotSettleNearUs -> memory.expansionPromiseTurn < 0 && (memory.landDispute >= DisputeLevel.Fierce
                || memory.landDispute >= DisputeLevel.Strong && memory.lastTurnLandDispute < DisputeLevel.Strong
                && memory.expansionPosture >= AggressivePosture.Medium)
            Demand.DoNotBuyLandNearUs -> memory.borderPromiseTurn < 0 && memory.landDispute >= DisputeLevel.Strong
                && memory.plotBuyingPosture >= AggressivePosture.Low
            Demand.DoNotSpreadReligion -> memory.negativeReligiousConversionPoints >=
                civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_DIPLO_HIT_THRESHOLD)
            Demand.DontSpyOnUs, Demand.DoNotDigInOurLands, Demand.DoNotAttackUs -> true
        }
    }
    //endregion

    //region What an AI answers when asked

    /** Whether the AI [civ] agrees to [demand] from [demander] */
    fun isDemandAcceptable(civ: Civilization, demander: Civilization, demand: Demand): Boolean = when (demand) {
        Demand.DoNotSettleNearUs -> isDontSettleAcceptable(civ, demander)
        Demand.DontSpyOnUs -> isStopSpyingAcceptable(civ, demander)
        Demand.DoNotSpreadReligion -> isStopSpreadingReligionAcceptable(civ, demander)
        Demand.DoNotDigInOurLands -> isStopDiggingAcceptable(civ, demander)
        // No original counterpart (only AIs warn humans): refusing means war, which only a war approach wants
        Demand.DoNotAttackUs -> civ.ai.diplomacy.approachTowards(demander) != MajorCivApproach.War
        // Only an AI asks a human for the border promise (DoPlotBuyingWarningStatement), never the other way round
        Demand.DoNotBuyLandNearUs -> false
    }

    /** CvDiplomacyAI::IsDontSettleAcceptable */
    fun isDontSettleAcceptable(civ: Civilization, demander: Civilization): Boolean {
        val ai = civ.ai.diplomacy
        val approach = ai.approachTowards(demander, hideTrueFeelings = true)
        if (approach == MajorCivApproach.Afraid) return true
        if (approach == MajorCivApproach.Hostile) return false
        if (ai.opinionOf(demander) == MajorCivOpinion.Unforgivable) return false
        val rules = civ.gameInfo.ruleset.ai
        val expansion = civ.ai.personalityAndGrandStrategy(Flavor.Expansion)
        var threshold = (rules.define(AiDefine.DONT_SETTLE_FLAVOR_BASE) - expansion) * rules.define(AiDefine.DONT_SETTLE_FLAVOR_MULTIPLIER)
        val friendly = approach == MajorCivApproach.Friendly
        if (friendly) threshold += rules.define(AiDefine.DONT_SETTLE_FRIENDLY)
        var militaryMod = when (WarStateAi.militaryStrengthComparedToUs(civ, demander)) {
            StrengthLevel.Pathetic -> if (friendly) 0 else rules.define(AiDefine.DONT_SETTLE_STRENGTH_PATHETIC)
            StrengthLevel.Weak -> if (friendly) 0 else rules.define(AiDefine.DONT_SETTLE_STRENGTH_WEAK)
            StrengthLevel.Poor -> if (friendly) 0 else rules.define(AiDefine.DONT_SETTLE_STRENGTH_POOR)
            StrengthLevel.Average -> rules.define(AiDefine.DONT_SETTLE_STRENGTH_AVERAGE)
            StrengthLevel.Strong -> rules.define(AiDefine.DONT_SETTLE_STRENGTH_STRONG)
            StrengthLevel.Powerful -> rules.define(AiDefine.DONT_SETTLE_STRENGTH_POWERFUL)
            StrengthLevel.Immense -> rules.define(AiDefine.DONT_SETTLE_STRENGTH_IMMENSE)
            null -> 0
        }
        militaryMod *= when (ai.memoryOf(demander).militaryPosture) {
            AggressivePosture.None -> rules.define(AiDefine.DONT_SETTLE_MOD_MILITARY_POSTURE_NONE)
            AggressivePosture.Low -> rules.define(AiDefine.DONT_SETTLE_MOD_MILITARY_POSTURE_LOW)
            AggressivePosture.Medium -> rules.define(AiDefine.DONT_SETTLE_MOD_MILITARY_POSTURE_MEDIUM)
            AggressivePosture.High -> rules.define(AiDefine.DONT_SETTLE_MOD_MILITARY_POSTURE_HIGH)
            AggressivePosture.Incredible -> rules.define(AiDefine.DONT_SETTLE_MOD_MILITARY_POSTURE_INCREDIBLE)
        }
        threshold += militaryMod / 100
        val roll = civ.ai.random("DiplomacyAI.IsDontSettleAcceptable" + demander.civID).nextInt(rules.define(AiDefine.DONT_SETTLE_RAND))
        return roll < threshold
    }

    /** CvDiplomacyAI::IsStopSpyingAcceptable */
    fun isStopSpyingAcceptable(civ: Civilization, demander: Civilization): Boolean {
        val approach = civ.ai.diplomacy.approachTowards(demander, hideTrueFeelings = true)
        if (approach == MajorCivApproach.Afraid) return true
        if (approach == MajorCivApproach.Hostile) return false
        return civ.ai.diplomacy.opinionOf(demander) != MajorCivOpinion.Unforgivable
    }

    /** CvDiplomacyAI::IsStopSpreadingReligionAcceptable: by the personality's religion flavor, else by the approach shown */
    fun isStopSpreadingReligionAcceptable(civ: Civilization, demander: Civilization): Boolean {
        val religionFlavor = civ.ai.personality(Flavor.Religion)
        if (religionFlavor <= 5) return true
        if (religionFlavor >= 8) return false
        return isAgreeableApproach(civ.ai.diplomacy.approachTowards(demander, hideTrueFeelings = true))
    }

    /** CvDiplomacyAI::IsStopDiggingAcceptable: by the personality's culture flavor and culture victory, else by the approach shown */
    fun isStopDiggingAcceptable(civ: Civilization, demander: Civilization): Boolean {
        val cultureFlavor = civ.ai.personality(Flavor.Culture)
        if (cultureFlavor < 5) return true
        if (cultureFlavor >= 8 || civ.ai.isGoingForCultureVictory()) return false
        return isAgreeableApproach(civ.ai.diplomacy.approachTowards(demander, hideTrueFeelings = true))
    }

    private fun isAgreeableApproach(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.Deceptive, MajorCivApproach.Afraid, MajorCivApproach.Friendly, MajorCivApproach.Neutral -> true
        MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Guarded -> false
    }
    //endregion
}
