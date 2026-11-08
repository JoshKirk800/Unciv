package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  CvDiplomacyAI::DoUpdateOpinions / DoUpdateOnePlayerOpinion / GetMajorCivOpinionWeight: one weight per known major (positive = bad), the sum of
 *  every BNW opinion term, mapped to the seven opinion levels by the OPINION_THRESHOLD_* defines.
 *
 *  The terms are written each turn into the [DiplomacyManager.diplomaticModifiers] of the pair, sign flipped (Unciv shows positive = good), so the
 *  diplomacy screen lists them and [DiplomacyManager.opinionOfOtherCiv] equals minus the weight. Terms the original computes but that Unciv has no game
 *  action for are left out: forgave for spying, intrigue shared, gave assistance / paid tribute (always 0 in the original), the city-state attack and
 *  bully promises (no such request in Unciv), the scenario modifiers.
 */
object OpinionAi {

    /** Every modifier this computation owns: all of them are rewritten on each update */
    private val OWNED_TERMS = DiplomaticModifiers.entries.filter { it.isOpinionTerm }

    fun updateOpinions(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            if (!other.isMajorCiv) continue
            updateOpinion(civ, other, rules)
        }
    }

    /** CvDiplomacyAI::DoUpdateOnePlayerOpinion (Unciv has no teams) */
    fun updateOpinion(civ: Civilization, other: Civilization, rules: AiRuleset = civ.gameInfo.ruleset.ai) {
        val diplomacy = civ.getDiplomacyManager(other) ?: return
        val weight = opinionWeight(civ, other, rules, diplomacy)
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.opinionWeight = weight
        memory.opinion = opinionLevel(weight, rules)
    }

    /** The OPINION_THRESHOLD_* mapping of DoUpdateOnePlayerOpinion */
    fun opinionLevel(weight: Int, rules: AiRuleset): MajorCivOpinion = when {
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_UNFORGIVABLE) -> MajorCivOpinion.Unforgivable
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_ENEMY) -> MajorCivOpinion.Enemy
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_COMPETITOR) -> MajorCivOpinion.Competitor
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_FAVORABLE) -> MajorCivOpinion.Neutral
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_FRIEND) -> MajorCivOpinion.Favorable
        weight >= rules.define(AiDefine.OPINION_THRESHOLD_ALLY) -> MajorCivOpinion.Friend
        else -> MajorCivOpinion.Ally
    }

    /**
     *  CvDiplomacyAI::GetMajorCivOpinionWeight. With [write], also sets each term on that diplomacy manager (opinion = minus the term)
     *  and removes the terms that are 0.
     */
    fun opinionWeight(civ: Civilization, other: Civilization, rules: AiRuleset, write: DiplomacyManager? = null): Int {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        var total = 0
        fun term(modifier: DiplomaticModifiers, weight: Int) {
            total += weight
            if (write == null) return
            if (weight == 0) write.removeModifier(modifier) else write.setModifier(modifier, -weight.toFloat())
        }
        if (write != null) for (modifier in OWNED_TERMS) write.removeModifier(modifier)

        // How nasty am I?
        term(DiplomaticModifiers.LeaderPersonality,
            (civ.ai.majorCivApproachBias(MajorCivApproach.Hostile) - civ.ai.majorCivApproachBias(MajorCivApproach.Friendly)) * 2)
        val land = landDisputeScore(memory.landDispute, rules)
        term(if (land < 0) DiplomaticModifiers.NoLandDispute else DiplomaticModifiers.LandDispute, land)
        term(DiplomaticModifiers.WonderDispute, wonderDisputeScore(memory.wonderDispute, rules))
        term(DiplomaticModifiers.MinorCivDispute, minorCivDisputeScore(memory.minorCivDispute, rules))
        term(DiplomaticModifiers.WarMongerer, warmongerThreatScore(civ, other))

        // Nice things
        term(DiplomaticModifiers.ReturnedCapturedUnits, memory.numCiviliansReturned * rules.define(AiDefine.OPINION_WEIGHT_RETURNED_CIVILIAN))
        term(DiplomaticModifiers.BuiltLandmarksForUs, landmarksScore(civ.getDiplomacyManager(other)?.landmarksBuiltForUs ?: 0, rules))
        term(DiplomaticModifiers.ResurrectedUs, if (memory.resurrectedUs) rules.define(AiDefine.OPINION_WEIGHT_RESURRECTED) else 0)
        term(DiplomaticModifiers.LiberatedCity, liberatedCitiesScore(memory.numCitiesLiberated, rules))
        // The embassy is state (TradeLogic sets it): its value is the opinion term
        if (write != null) {
            val embassyWeight = -rules.define(AiDefine.OPINION_WEIGHT_EMBASSY).toFloat()
            if (write.hasModifier(DiplomaticModifiers.EstablishedEmbassy)) write.setModifier(DiplomaticModifiers.EstablishedEmbassy, embassyWeight)
            if (write.hasModifier(DiplomaticModifiers.SharedEmbassies)) write.setModifier(DiplomaticModifiers.SharedEmbassies, embassyWeight)
            if (write.hasModifier(DiplomaticModifiers.ReceivedEmbassy)) write.setModifier(DiplomaticModifiers.ReceivedEmbassy, 0f)
        }
        if (hasEmbassyWith(civ, other)) total += rules.define(AiDefine.OPINION_WEIGHT_EMBASSY)

        // Asked us to do things we don't like
        term(DiplomaticModifiers.AskedUsNotToSettle, if (memory.noSettleRequestEverAsked) rules.define(AiDefine.OPINION_WEIGHT_ASKED_NO_SETTLE) else 0)
        term(DiplomaticModifiers.AskedUsToStopSpying, if (memory.stopSpyingRequestEverAsked) rules.define(AiDefine.OPINION_WEIGHT_ASKED_STOP_SPYING) else 0)
        term(DiplomaticModifiers.UnacceptableDemands, if (memory.demandCounter != -1) rules.define(AiDefine.OPINION_WEIGHT_MADE_DEMAND_OF_US) else 0)
        term(DiplomaticModifiers.StealingTerritory, memory.numTimesCultureBombed * rules.define(AiDefine.OPINION_WEIGHT_CULTURE_BOMBED))
        val eraName = DiplomacyTurn.gameEraName(civ)
        val religionEmphasis = rules.eraDiplomacy(eraName).diploEmphasisReligion
        term(DiplomaticModifiers.ReligiousConversions,
            memory.negativeReligiousConversionPoints * religionEmphasis * rules.define(AiDefine.OPINION_WEIGHT_PER_NEGATIVE_CONVERSION))
        val weFollowTheirs = hasOthersReligionInMostCities(civ, other)
        term(DiplomaticModifiers.WeAdoptedTheirReligion, if (weFollowTheirs) rules.define(AiDefine.OPINION_WEIGHT_ADOPTING_HIS_RELIGION) * religionEmphasis else 0)
        term(DiplomaticModifiers.TheyAdoptedOurReligion,
            if (!weFollowTheirs && hasOthersReligionInMostCities(other, civ)) rules.define(AiDefine.OPINION_WEIGHT_ADOPTING_MY_RELIGION) * religionEmphasis else 0)
        val latePolicies = rules.eraDiplomacy(eraName).diploEmphasisLatePolicies
        val ourIdeology = civ.getIdeology()
        val theirIdeology = other.getIdeology()
        if (ourIdeology != null && theirIdeology != null) {
            if (ourIdeology == theirIdeology) term(DiplomaticModifiers.SameIdeology, rules.define(AiDefine.OPINION_WEIGHT_SAME_LATE_POLICIES) * latePolicies)
            else term(DiplomaticModifiers.DifferentIdeology, rules.define(AiDefine.OPINION_WEIGHT_DIFFERENT_LATE_POLICIES) * latePolicies)
        }
        term(DiplomaticModifiers.SpiedOnUs, memory.numTimesRobbed * rules.define(AiDefine.OPINION_WEIGHT_ROBBED_BY))

        // Broken promises
        term(DiplomaticModifiers.BetrayedPromiseToNotAttackUs, if (memory.brokenMilitaryPromise) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_MILITARY_PROMISE) else 0)
        val brokenWithAnybody = !memory.brokenMilitaryPromise && hasBrokenMilitaryPromiseWithAnybody(other)
        term(DiplomaticModifiers.BetrayedPromiseToNotAttackOtherCiv, if (brokenWithAnybody) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_MILITARY_PROMISE_WORLD) else 0)
        term(DiplomaticModifiers.RefusedToPromiseNotToAttackUs,
            if (!memory.brokenMilitaryPromise && !brokenWithAnybody && memory.ignoredMilitaryPromise) rules.define(AiDefine.OPINION_WEIGHT_IGNORED_MILITARY_PROMISE) else 0)
        val opinionDuration = DiplomacyTurn.opinionDurationPercent(civ)
        term(DiplomaticModifiers.BetrayedPromiseToNotSettleCitiesNearUs, PromiseAi.brokenExpansionPromiseScore(memory, opinionDuration, rules))
        term(DiplomaticModifiers.RefusedToNotSettleCitiesNearUs, PromiseAi.ignoredExpansionPromiseScore(memory, opinionDuration, rules))
        term(DiplomaticModifiers.BrokenBorderPromise, PromiseAi.brokenBorderPromiseScore(memory, opinionDuration, rules))
        term(DiplomaticModifiers.IgnoredBorderPromise, PromiseAi.ignoredBorderPromiseScore(memory, opinionDuration, rules))
        term(DiplomaticModifiers.BetrayedPromiseToNotSpreadReligionToUs,
            if (memory.brokenNoConvertPromise) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_NO_CONVERT_PROMISE) * religionEmphasis else 0)
        term(DiplomaticModifiers.RefusedToNotSpreadReligionToUs,
            if (!memory.brokenNoConvertPromise && memory.ignoredNoConvertPromise) rules.define(AiDefine.OPINION_WEIGHT_IGNORED_NO_CONVERT_PROMISE) * religionEmphasis else 0)
        term(DiplomaticModifiers.BetrayedPromiseToNotDigInOurLands, if (memory.brokenNoDiggingPromise) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_NO_DIG_PROMISE) else 0)
        term(DiplomaticModifiers.IgnoredRequestToStopDigging,
            if (!memory.brokenNoDiggingPromise && memory.ignoredNoDiggingPromise) rules.define(AiDefine.OPINION_WEIGHT_IGNORED_NO_DIG_PROMISE) else 0)
        term(DiplomaticModifiers.BetrayedPromiseToNotSendingSpiesToUs, if (memory.brokenSpyPromise) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_SPY_PROMISE) else 0)
        term(DiplomaticModifiers.RefusedToNotSendingSpiesToUs,
            if (!memory.brokenSpyPromise && memory.ignoredSpyPromise) rules.define(AiDefine.OPINION_WEIGHT_IGNORED_SPY_PROMISE) else 0)
        term(DiplomaticModifiers.BrokenCoopWarPromise, if (memory.brokenCoopWarPromise) rules.define(AiDefine.OPINION_WEIGHT_BROKEN_COOP_WAR_PROMISE) else 0)

        // Protected minors
        term(DiplomaticModifiers.DestroyedProtectedMinor, protectedMinorKilledScore(memory, rules))
        term(DiplomaticModifiers.AttackedProtectedMinor, protectedMinorAttackedScore(memory, rules))
        term(DiplomaticModifiers.BulliedProtectedMinor, protectedMinorBulliedScore(memory, rules))
        term(DiplomaticModifiers.SidedWithProtectedMinor, if (isAngryAboutSidedWithProtectedMinor(memory, rules)) rules.define(AiDefine.OPINION_WEIGHT_SIDED_WITH_THEIR_MINOR) else 0)

        // Declaration of friendship
        term(DiplomaticModifiers.DeclarationOfFriendship, if (isDoFAccepted(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DOF) else 0)
        term(DiplomaticModifiers.DeclaredFriendshipWithOurAllies, if (isPlayerDoFWithAnyFriend(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_FRIEND) else 0)
        term(DiplomaticModifiers.DeclaredFriendshipWithOurEnemies, if (isPlayerDoFWithAnyEnemy(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DOF_WITH_ENEMY) else 0)

        // Friends not getting along: the worst of the traitor terms
        term(DiplomaticModifiers.BetrayedDeclarationOfFriendship, traitorScore(civ, other, rules))

        // Denouncing
        term(DiplomaticModifiers.Denunciation, if (isDenouncedPlayer(other, civ)) rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ME) else 0)
        term(DiplomaticModifiers.WeDenouncedThem, if (isDenouncedPlayer(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_THEM) else 0)
        term(DiplomaticModifiers.DenouncedOurAllies, if (isPlayerDenouncedFriend(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_FRIEND) else 0)
        term(DiplomaticModifiers.DenouncedOurEnemies, if (isPlayerDenouncedEnemy(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ENEMY) else 0)

        term(DiplomaticModifiers.RecklessExpander, if (WarStateAi.isPlayerRecklessExpander(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_RECKLESS_EXPANDER) else 0)

        // June 2011 additions
        term(DiplomaticModifiers.TradedWithUs, recentTradeScore(memory.recentTradeValue, rules))
        term(DiplomaticModifiers.SharedEnemy, commonFoeScore(memory.commonFoeValue, rules))
        val assist = recentAssistScore(memory.recentAssistValue, rules)
        term(if (assist < 0) DiplomaticModifiers.HelpedUs else DiplomaticModifiers.RefusedToHelpUs, assist)
        term(DiplomaticModifiers.UsedNuclearWeapons, if (memory.numTimesNuked > 0) rules.define(AiDefine.OPINION_WEIGHT_NUKED_MAX) else 0)
        term(DiplomaticModifiers.CapturedOurCities, if (isCapitalCapturedBy(civ, other)) rules.define(AiDefine.OPINION_WEIGHT_CAPTURED_CAPITAL) else 0)

        // World Congress
        val league = civ.gameInfo.league
        val turn = civ.gameInfo.turns
        val ourMemory = league?.members?.get(civ.civID)?.alignmentMemory?.get(other.civID)
        if (ourMemory != null) {
            term(DiplomaticModifiers.LikedTheirProposal, likedTheirProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.DislikedTheirProposal, dislikedTheirProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.SupportedOurProposal, supportedMyProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.FoiledOurProposal, foiledMyProposalScore(ourMemory, turn, rules))
            term(DiplomaticModifiers.SupportedOurHosting, supportedMyHostingScore(ourMemory, turn, rules))
        }
        return total
    }

    //region The terms (CvDiplomacyAI::Get*Score)

    /** GetLandDisputeLevelScore */
    fun landDisputeScore(level: DisputeLevel, rules: AiRuleset): Int = when (level) {
        DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_LAND_FIERCE)
        DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_LAND_STRONG)
        DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_LAND_WEAK)
        DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_LAND_NONE)
    }

    /** GetWonderDisputeLevelScore */
    fun wonderDisputeScore(level: DisputeLevel, rules: AiRuleset): Int = when (level) {
        DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_FIERCE)
        DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_STRONG)
        DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_WEAK)
        DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_WONDER_NONE)
    }

    /** GetMinorCivDisputeLevelScore */
    fun minorCivDisputeScore(level: DisputeLevel, rules: AiRuleset): Int = when (level) {
        DisputeLevel.Fierce -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_FIERCE)
        DisputeLevel.Strong -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_STRONG)
        DisputeLevel.Weak -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_WEAK)
        DisputeLevel.None -> rules.define(AiDefine.OPINION_WEIGHT_MINOR_CIV_NONE)
    }

    /** GetWarmongerThreatScore: the warmonger score, counted from 5 */
    fun warmongerThreatScore(civ: Civilization, other: Civilization): Int {
        val score = DiplomacyTurn.warmongerScore(civ, other)
        return if (score < 5) 0 else score
    }

    /** GetLandmarksBuiltForMeScore: the first counts fully, each later one a third (integer division as the original) */
    fun landmarksScore(count: Int, rules: AiRuleset): Int {
        if (count <= 0) return 0
        val weight = rules.define(AiDefine.OPINION_WEIGHT_BUILT_LANDMARK)
        return weight + (weight / 3) * (count - 1)
    }

    /** GetLiberatedCitiesScore */
    fun liberatedCitiesScore(count: Int, rules: AiRuleset): Int = when {
        count >= 3 -> rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_THREE_CITIES)
        count >= 2 -> rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_TWO_CITIES)
        count >= 1 -> rules.define(AiDefine.OPINION_WEIGHT_LIBERATED_ONE_CITY)
        else -> 0
    }

    /** CvTeam::HasEmbassyAtTeam: we have an embassy in their capital */
    fun hasEmbassyWith(civ: Civilization, other: Civilization): Boolean {
        val diplomacy = civ.getDiplomacyManager(other) ?: return false
        return diplomacy.hasModifier(DiplomaticModifiers.EstablishedEmbassy) || diplomacy.hasModifier(DiplomaticModifiers.SharedEmbassies)
    }

    /** CvPlayerReligions::HasOthersReligionInMostCities: [civ] founded no religion and more than half its cities follow [other]'s */
    fun hasOthersReligionInMostCities(civ: Civilization, other: Civilization): Boolean {
        if (civ.religionManager.religionState >= ReligionState.Religion) return false
        val theirReligion = other.religionManager.religion ?: return false
        if (other.religionManager.religionState < ReligionState.Religion) return false
        val following = civ.cities.count { it.religion.getMajorityReligionName() == theirReligion.name }
        return following * 2 > civ.cities.size
    }

    private fun hasBrokenMilitaryPromiseWithAnybody(other: Civilization): Boolean =
        other.gameInfo.civilizations.any { it != other && it.isMajorCiv && it.ai.diplomacy.memories[other.civID]?.brokenMilitaryPromise == true }

    /** GetAngryAboutProtectedMinorKilledScore */
    fun protectedMinorKilledScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (!isAngryAboutProtectedMinorKilled(memory, rules)) return 0
        var weight = rules.define(AiDefine.OPINION_WEIGHT_KILLED_PROTECTED_MINOR)
        if (memory.numProtectedMinorsKilled > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_KILLED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** GetAngryAboutProtectedMinorAttackedScore */
    fun protectedMinorAttackedScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (!isAngryAboutProtectedMinorAttacked(memory, rules)) return 0
        var weight = if (memory.turnsSinceAttackedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_RECENTLY_NUM_TURNS))
            rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_RECENTLY)
        else rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_WHILE_AGO)
        if (memory.numProtectedMinorsAttacked > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** GetAngryAboutProtectedMinorBulliedScore */
    fun protectedMinorBulliedScore(memory: MajorCivMemory, rules: AiRuleset): Int {
        if (memory.numProtectedMinorsBullied <= 0 || memory.turnsSinceBulliedProtectedMinor < 0 ||
            memory.turnsSinceBulliedProtectedMinor >= rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)) return 0
        var weight = if (memory.turnsSinceBulliedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_RECENTLY_NUM_TURNS))
            rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_RECENTLY)
        else rules.define(AiDefine.OPINION_WEIGHT_BULLIED_PROTECTED_MINOR_WHILE_AGO)
        if (memory.numProtectedMinorsBullied > 1) weight += rules.define(AiDefine.OPINION_WEIGHT_BULLIED_MANY_PROTECTED_MINORS)
        return weight
    }

    /** IsAngryAboutProtectedMinorKilled */
    fun isAngryAboutProtectedMinorKilled(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        memory.numProtectedMinorsKilled > 0 && memory.turnsSinceKilledProtectedMinor > -1 &&
            memory.turnsSinceKilledProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_KILLED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    /** IsAngryAboutProtectedMinorAttacked: anger over a killed city-state takes precedence */
    fun isAngryAboutProtectedMinorAttacked(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        !isAngryAboutProtectedMinorKilled(memory, rules) && memory.numProtectedMinorsAttacked > 0 && memory.turnsSinceAttackedProtectedMinor > -1 &&
            memory.turnsSinceAttackedProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_ATTACKED_PROTECTED_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    private fun isAngryAboutSidedWithProtectedMinor(memory: MajorCivMemory, rules: AiRuleset): Boolean =
        memory.turnsSinceSidedWithProtectedMinor > -1 &&
            memory.turnsSinceSidedWithProtectedMinor < rules.define(AiDefine.OPINION_WEIGHT_SIDED_WITH_THEIR_MINOR_NUM_TURNS_UNTIL_FORGIVEN)

    /** CvDiplomacyAI::IsDoFAccepted: Unciv keeps the friendship as [DiplomacyFlags.DeclarationOfFriendship] */
    fun isDoFAccepted(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true

    /** CvDiplomacyAI::IsDenouncedPlayer: [civ] has denounced [other] (Unciv: [DiplomacyFlags.Denunciation] on civ's side) */
    fun isDenouncedPlayer(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.Denunciation) == true

    /** IsPlayerDoFwithAnyFriend: they have a DoF with someone we have a DoF with */
    fun isPlayerDoFWithAnyFriend(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacy.values.any { it.otherCiv != other && it.hasFlag(DiplomacyFlags.DeclarationOfFriendship) && isDoFAccepted(other, it.otherCiv) }

    /** IsPlayerDoFwithAnyEnemy: they have a DoF with someone we have denounced */
    fun isPlayerDoFWithAnyEnemy(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacy.values.any { it.otherCiv != other && it.hasFlag(DiplomacyFlags.Denunciation) && isDoFAccepted(other, it.otherCiv) }

    /** IsPlayerDenouncedFriend: they denounced someone we have a DoF with */
    fun isPlayerDenouncedFriend(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacy.values.any { it.otherCiv != other && it.hasFlag(DiplomacyFlags.DeclarationOfFriendship) && isDenouncedPlayer(other, it.otherCiv) }

    /** IsPlayerDenouncedEnemy: they denounced someone we have denounced */
    fun isPlayerDenouncedEnemy(civ: Civilization, other: Civilization): Boolean =
        civ.diplomacy.values.any { it.otherCiv != other && it.hasFlag(DiplomacyFlags.Denunciation) && isDenouncedPlayer(other, it.otherCiv) }

    /** IsUntrustworthyFriend: declared war on a friend, or denounced at least 2 friends */
    fun isUntrustworthyFriend(civ: Civilization): Boolean =
        weDeclaredWarOnFriendCount(civ) > 0 || weDenouncedFriendCount(civ) >= 2

    /** GetWeDenouncedFriendCount: how many players remember [civ] denouncing them while friends */
    fun weDenouncedFriendCount(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it != civ && it.isMajorCiv && it.ai.diplomacy.memories[civ.civID]?.friendDenouncedUs == true }

    /** GetWeDeclaredWarOnFriendCount */
    fun weDeclaredWarOnFriendCount(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it != civ && it.isMajorCiv && it.ai.diplomacy.memories[civ.civID]?.friendDeclaredWarOnUs == true }

    /** The traitor block of GetMajorCivOpinionWeight: the largest of five terms */
    fun traitorScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        val memory = civ.ai.diplomacy.memoryOf(other)
        var traitor = friendDenouncementScore(civ, other, rules)
        // GetWeDenouncedFriendScore
        traitor = maxOf(traitor, weDenouncedFriendCount(other) * rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_FRIEND_EACH))
        // GetFriendDenouncedUsScore
        if (memory.friendDenouncedUs) traitor = maxOf(traitor, rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_ME_FRIENDS))
        // GetWeDeclaredWarOnFriendScore: the decaying values every player keeps for them
        var declaredWarOnFriendSum = 0
        for (third in civ.gameInfo.civilizations) if (third.isMajorCiv) declaredWarOnFriendSum += third.ai.diplomacy.memories[other.civID]?.declaredWarOnFriendValue ?: 0
        traitor = maxOf(traitor, declaredWarOnFriendSum /
            (rules.define(AiDefine.DECLARED_WAR_ON_FRIEND_PER_OPINION_WEIGHT) * DiplomacyTurn.opinionDurationPercent(civ)))
        // GetFriendDeclaredWarOnUsScore
        if (memory.friendDeclaredWarOnUs) traitor = maxOf(traitor, rules.define(AiDefine.OPINION_WEIGHT_WAR_ME_FRIENDS))
        return traitor
    }

    /** GetFriendDenouncementScore: their (trustworthy) former friends who denounced them, weighted by whom we like more */
    fun friendDenouncementScore(civ: Civilization, other: Civilization, rules: AiRuleset): Int {
        var score = 0
        val ourOpinionOfThem = civ.ai.diplomacy.opinionOf(other)
        for ((denouncerId, theirMemory) in other.ai.diplomacy.memories) {
            if (!theirMemory.friendDenouncedUs || denouncerId == civ.civID) continue
            val denouncer = civ.gameInfo.getCivilizationOrNull(denouncerId) ?: continue
            if (isUntrustworthyFriend(denouncer)) continue
            val ourOpinionOfDenouncer = civ.ai.diplomacy.opinionOf(denouncer)
            if (ourOpinionOfDenouncer > ourOpinionOfThem) score += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_FRIEND_EACH)
            else if (ourOpinionOfDenouncer < ourOpinionOfThem) score += rules.define(AiDefine.OPINION_WEIGHT_DENOUNCED_BY_FRIEND_DONT_LIKE)
        }
        return score
    }

    /** GetRecentTradeScore */
    fun recentTradeScore(value: Int, rules: AiRuleset): Int =
        if (value <= 0) 0 else maxOf(-value / rules.define(AiDefine.DEAL_VALUE_PER_OPINION_WEIGHT), rules.define(AiDefine.OPINION_WEIGHT_TRADE_MAX))

    /** GetCommonFoeScore */
    fun commonFoeScore(value: Int, rules: AiRuleset): Int =
        if (value <= 0) 0 else maxOf(-value / rules.define(AiDefine.COMMON_FOE_VALUE_PER_OPINION_WEIGHT), rules.define(AiDefine.OPINION_WEIGHT_COMMON_FOE_MAX))

    /** GetRecentAssistScore */
    fun recentAssistScore(value: Int, rules: AiRuleset): Int {
        if (value == 0) return 0
        val max = rules.define(AiDefine.OPINION_WEIGHT_ASSIST_MAX)
        return (value / rules.define(AiDefine.DEAL_VALUE_PER_OPINION_WEIGHT)).coerceIn(-max, max)
    }

    /** IsCapitalCapturedBy: [other] holds our original capital */
    fun isCapitalCapturedBy(civ: Civilization, other: Civilization): Boolean =
        other.cities.any { it.isOriginalCapital && it.foundingCivObject == civ }

    private fun recent(turnSet: Int, turn: Int, numTurns: Int): Boolean = turnSet >= 0 && turn - turnSet < numTurns

    /** The opposing memory does not exist or is older (DLL: iTurnsSince < iOpposingTurnsSince) */
    private fun newerThan(turnSet: Int, opposing: Int): Boolean = opposing < 0 || turnSet > opposing

    fun likedTheirProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.likedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.likedProposalTurn, memory.dislikedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_WE_LIKED_THEIR_PROPOSAL) else 0

    fun dislikedTheirProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.dislikedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.dislikedProposalTurn, memory.likedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_WE_DISLIKED_THEIR_PROPOSAL) else 0

    fun supportedMyProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.supportedProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.supportedProposalTurn, memory.foiledProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_PROPOSAL) else 0

    fun foiledMyProposalScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.foiledProposalTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL_NUM_TURNS)) &&
            newerThan(memory.foiledProposalTurn, memory.supportedProposalTurn)) rules.define(AiDefine.OPINION_WEIGHT_THEY_FOILED_OUR_PROPOSAL) else 0

    fun supportedMyHostingScore(memory: LeagueAlignmentMemory, turn: Int, rules: AiRuleset): Int =
        if (recent(memory.supportedHostingTurn, turn, rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING_NUM_TURNS)))
            rules.define(AiDefine.OPINION_WEIGHT_THEY_SUPPORTED_OUR_HOSTING) else 0
    //endregion
}
