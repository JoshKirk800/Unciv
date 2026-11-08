package com.unciv.logic.automation.diplomacy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Readonly
import com.unciv.models.ruleset.ai.MajorCivApproach

/** A coop war agreement with one partner against one target (CvDiplomacyAI::m_ppaacCoopWarAcceptedState / m_ppaaiCoopWarCounter) */
class CoopWarEntry() : IsPartOfGameInfoSerialization {
    /** null = NO_COOP_WAR_STATE */
    var state: CoopWarState? = null
    /** -1 = never asked, -666 = cancelled */
    var counter = -1

    constructor(state: CoopWarState?, counter: Int) : this() {
        this.state = state
        this.counter = counter
    }

    fun clone() = CoopWarEntry(state, counter)
}

/**
 *  What one player's CvDiplomacyAI keeps about another player (the m_pa* / m_ppa* arrays indexed by that player), by civID in [DiplomacyAi].
 *  Counters use the original's sentinels: -1 = never, -666 = cancelled. Every field has a default, so old saves load with "nothing known".
 */
class MajorCivMemory : IsPartOfGameInfoSerialization {
    // Opinion and approach
    var opinion = MajorCivOpinion.Neutral
    /** GetMajorCivOpinionWeight of the last update (positive = bad) */
    var opinionWeight = 0
    /** null = NO_MAJOR_CIV_APPROACH (not evaluated yet: reads as Neutral) */
    var approach: MajorCivApproach? = null
    var warFace: WarFace? = null

    // War
    var warGoal: WarGoal? = null
    var wantPeaceCounter = 0
    /** CvDiplomacyAI::IsWantsPeaceWithPlayer as of the last war goal update */
    var wantsPeace = false
    var warProjection: WarProjection? = null
    var lastWarProjection: WarProjection? = null
    var warDamageLevel = WarDamageLevel.None
    /** CvDiplomacyAI::m_paiWarValueLost: what we lost in wars against them */
    var warValueLost = 0
    /** CvDiplomacyAI::m_ppaiOtherPlayerWarValueLost[them][us]: what they lost to us, as we estimate it */
    var theirWarValueLostToUs = 0
    var theirWarDamageLevel = WarDamageLevel.None
    var economicStrength: StrengthLevel? = null
    var militaryThreat = AiThreat.None
    var warmongerThreat = AiThreat.None
    /** CvDiplomacyAI::m_paiOtherPlayerWarmongerAmount */
    var warmongerAmount = 0
    var treatyWillingToOffer = PeaceTreatyType.None
    var treatyWillingToAccept = PeaceTreatyType.None
    var numWarsFought = 0
    /** CvTeam::GetTurnMadePeaceTreatyWithTeam: game turn, -1 = never */
    var turnMadePeace = -1
    /** CvTeam::GetNumTurnsLockedIntoWar */
    var turnsLockedIntoWar = 0
    /** Our attack operation against them exists (CvMilitaryAI::GetSneakAttackOperation stand-in: the army has been formed, see [WarAi]) */
    var attackOperation = false

    // Disputes and postures
    var landDispute = DisputeLevel.None
    var lastTurnLandDispute = DisputeLevel.None
    var wonderDispute = DisputeLevel.None
    var minorCivDispute = DisputeLevel.None
    var numWondersBeatenTo = 0
    var expansionPosture = AggressivePosture.None
    var plotBuyingPosture = AggressivePosture.None
    var militaryPosture = AggressivePosture.None
    var lastTurnMilitaryPosture = AggressivePosture.None

    // Memories feeding opinion
    var recentTradeValue = 0
    var commonFoeValue = 0
    /** CvCityAI::AI_GetNumPlotsAcquiredByOtherPlayer summed over our cities: tiles they bought within 3 tiles of our cities */
    var numPlotsBoughtNearUs = 0
    var recentAssistValue = 0
    var numCiviliansReturned = 0
    var numCitiesLiberated = 0
    var resurrectedUs = false
    var numTimesCultureBombed = 0
    var negativeReligiousConversionPoints = 0
    var numTimesNuked = 0
    var numTimesRobbed = 0
    var turnsSinceAttackedProtectedMinor = -1
    var numProtectedMinorsAttacked = 0
    var turnsSinceKilledProtectedMinor = -1
    var numProtectedMinorsKilled = 0
    var turnsSinceBulliedProtectedMinor = -1
    var numProtectedMinorsBullied = 0
    var turnsSinceSidedWithProtectedMinor = -1

    // Friendship and denouncements
    /** CvDiplomacyAI::m_paiDoFCounter: turns since the DoF statement/acceptance, -1 never, -666 broken */
    var doFCounter = -1
    /** CvDiplomacyAI::m_paiDenouncedPlayerCounter: turns since we denounced them, -1 never */
    var denouncedCounter = -1
    /** The denouncement we track the expiry of (Unciv keeps the denouncement itself as [com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation]) */
    var denouncementActive = false
    var friendDenouncedUs = false
    var friendDeclaredWarOnUs = false
    /** CvDiplomacyAI::m_paiDeclaredWarOnFriendValue (decays) */
    var declaredWarOnFriendValue = 0

    // Coop wars with them as partner, by target civID
    var coopWars = HashMap<String, CoopWarEntry>()

    // Requests, demands, promises
    /** CvDiplomacyAI::m_paiDemandCounter: turns since they made a demand of us, -1 never */
    var demandCounter = -1
    var noSettleRequestEverAsked = false
    var stopSpyingRequestEverAsked = false
    var madeMilitaryPromise = false
    var brokenMilitaryPromise = false
    var ignoredMilitaryPromise = false
    var brokenExpansionPromiseValue = 0
    var ignoredExpansionPromiseValue = 0
    var brokenBorderPromiseValue = 0
    var ignoredBorderPromiseValue = 0
    var brokenAttackCityStatePromise = false
    var ignoredAttackCityStatePromise = false
    var brokenBullyCityStatePromise = false
    var ignoredBullyCityStatePromise = false
    var brokenSpyPromise = false
    var ignoredSpyPromise = false
    var brokenNoConvertPromise = false
    var ignoredNoConvertPromise = false
    var brokenNoDiggingPromise = false
    var ignoredNoDiggingPromise = false
    var brokenCoopWarPromise = false
    var wantsResearchAgreement = false

    /** The diplo log (DoAddNewStatementToDiploLog): game turn each statement was last sent */
    var statementTurns = HashMap<DiploStatement, Int>()
    /** Turn of the last statement or offer to them (one per player per turn, DoContactPlayer) */
    var lastContactTurn = -1
    /** CvTeam::GetTurnsSinceMeetingTeam: game turn of first contact, -1 = before this port (counts as long ago) */
    var turnMet = -1

    fun clone(): MajorCivMemory {
        val copy = MajorCivMemory()
        copy.opinion = opinion; copy.opinionWeight = opinionWeight; copy.approach = approach; copy.warFace = warFace
        copy.warGoal = warGoal; copy.wantPeaceCounter = wantPeaceCounter; copy.wantsPeace = wantsPeace; copy.warProjection = warProjection
        copy.lastWarProjection = lastWarProjection; copy.warDamageLevel = warDamageLevel; copy.warValueLost = warValueLost
        copy.theirWarValueLostToUs = theirWarValueLostToUs; copy.theirWarDamageLevel = theirWarDamageLevel
        copy.economicStrength = economicStrength; copy.militaryThreat = militaryThreat; copy.warmongerThreat = warmongerThreat
        copy.warmongerAmount = warmongerAmount; copy.treatyWillingToOffer = treatyWillingToOffer
        copy.treatyWillingToAccept = treatyWillingToAccept; copy.numWarsFought = numWarsFought; copy.turnMadePeace = turnMadePeace
        copy.turnsLockedIntoWar = turnsLockedIntoWar; copy.attackOperation = attackOperation
        copy.landDispute = landDispute; copy.lastTurnLandDispute = lastTurnLandDispute; copy.wonderDispute = wonderDispute
        copy.minorCivDispute = minorCivDispute; copy.numWondersBeatenTo = numWondersBeatenTo; copy.expansionPosture = expansionPosture
        copy.plotBuyingPosture = plotBuyingPosture; copy.militaryPosture = militaryPosture
        copy.lastTurnMilitaryPosture = lastTurnMilitaryPosture
        copy.recentTradeValue = recentTradeValue; copy.commonFoeValue = commonFoeValue; copy.recentAssistValue = recentAssistValue
        copy.numPlotsBoughtNearUs = numPlotsBoughtNearUs
        copy.numCiviliansReturned = numCiviliansReturned; copy.numCitiesLiberated = numCitiesLiberated; copy.resurrectedUs = resurrectedUs
        copy.numTimesCultureBombed = numTimesCultureBombed
        copy.negativeReligiousConversionPoints = negativeReligiousConversionPoints
        copy.numTimesNuked = numTimesNuked; copy.numTimesRobbed = numTimesRobbed
        copy.turnsSinceAttackedProtectedMinor = turnsSinceAttackedProtectedMinor
        copy.numProtectedMinorsAttacked = numProtectedMinorsAttacked
        copy.turnsSinceKilledProtectedMinor = turnsSinceKilledProtectedMinor; copy.numProtectedMinorsKilled = numProtectedMinorsKilled
        copy.turnsSinceBulliedProtectedMinor = turnsSinceBulliedProtectedMinor; copy.numProtectedMinorsBullied = numProtectedMinorsBullied
        copy.turnsSinceSidedWithProtectedMinor = turnsSinceSidedWithProtectedMinor
        copy.doFCounter = doFCounter; copy.denouncedCounter = denouncedCounter; copy.denouncementActive = denouncementActive
        copy.friendDenouncedUs = friendDenouncedUs; copy.friendDeclaredWarOnUs = friendDeclaredWarOnUs
        copy.declaredWarOnFriendValue = declaredWarOnFriendValue
        for ((target, entry) in coopWars) copy.coopWars[target] = entry.clone()
        copy.demandCounter = demandCounter
        copy.noSettleRequestEverAsked = noSettleRequestEverAsked; copy.stopSpyingRequestEverAsked = stopSpyingRequestEverAsked
        copy.madeMilitaryPromise = madeMilitaryPromise; copy.brokenMilitaryPromise = brokenMilitaryPromise
        copy.ignoredMilitaryPromise = ignoredMilitaryPromise
        copy.brokenExpansionPromiseValue = brokenExpansionPromiseValue; copy.ignoredExpansionPromiseValue = ignoredExpansionPromiseValue
        copy.brokenBorderPromiseValue = brokenBorderPromiseValue; copy.ignoredBorderPromiseValue = ignoredBorderPromiseValue
        copy.brokenAttackCityStatePromise = brokenAttackCityStatePromise
        copy.ignoredAttackCityStatePromise = ignoredAttackCityStatePromise
        copy.brokenBullyCityStatePromise = brokenBullyCityStatePromise; copy.ignoredBullyCityStatePromise = ignoredBullyCityStatePromise
        copy.brokenSpyPromise = brokenSpyPromise; copy.ignoredSpyPromise = ignoredSpyPromise
        copy.brokenNoConvertPromise = brokenNoConvertPromise; copy.ignoredNoConvertPromise = ignoredNoConvertPromise
        copy.brokenNoDiggingPromise = brokenNoDiggingPromise; copy.ignoredNoDiggingPromise = ignoredNoDiggingPromise
        copy.brokenCoopWarPromise = brokenCoopWarPromise; copy.wantsResearchAgreement = wantsResearchAgreement
        copy.statementTurns.putAll(statementTurns); copy.lastContactTurn = lastContactTurn; copy.turnMet = turnMet
        return copy
    }
}

/**
 *  The CvDiplomacyAI state of one civilization (`civ.ai.diplomacy`) and the read API other AI code uses.
 *  The logic that fills it is in [DiplomacyTurn] (assessment, opinions, approaches each turn), [DiplomacyEvents] (what happens in the game)
 *  and [DiplomacyAutomation] (what the AI does). Values are those of the last update: nothing is computed on read.
 */
class DiplomacyAi : IsPartOfGameInfoSerialization {
    /** By civID: majors and city-states we know */
    var memories = HashMap<String, MajorCivMemory>()
    /** CvDiplomacyAI::m_eDemandTargetPlayer, civID */
    var demandTarget: String? = null
    var demandReady = false
    /** CvDiplomacyAI::GetTotalLandDisputeLevel as of the last [DiplomacyTurn.doTurn] (the disputes it sums change only there) */
    var lastTotalLandDisputeLevel = 0

    @Transient lateinit var civ: Civilization

    fun clone(): DiplomacyAi {
        val copy = DiplomacyAi()
        for ((id, memory) in memories) copy.memories[id] = memory.clone()
        copy.demandTarget = demandTarget
        copy.demandReady = demandReady
        copy.lastTotalLandDisputeLevel = lastTotalLandDisputeLevel
        return copy
    }

    /** The memory of [other], created empty on first use */
    fun memoryOf(other: Civilization): MajorCivMemory = memories.getOrPut(other.civID) { MajorCivMemory() }

    //region Read API (CvDiplomacyAI getters)

    /** CvDiplomacyAI::IsPlayerValid */
    fun isPlayerValid(other: Civilization): Boolean = WarStateAi.isPlayerValid(civ, other)

    /**
     *  CvDiplomacyAI::GetMajorCivApproach. With [hideTrueFeelings] the approach the AI shows: Deceptive looks Friendly, War shows its war face.
     *  Before the first evaluation the approach is Neutral.
     */
    @Readonly
    fun approachTowards(other: Civilization, hideTrueFeelings: Boolean = false): MajorCivApproach {
        val memory = memories[other.civID] ?: return MajorCivApproach.Neutral
        val approach = memory.approach ?: return MajorCivApproach.Neutral
        if (!hideTrueFeelings) return approach
        return when (approach) {
            MajorCivApproach.Deceptive -> MajorCivApproach.Friendly
            MajorCivApproach.War -> when (memory.warFace) {
                WarFace.Hostile -> MajorCivApproach.Hostile
                WarFace.Friendly -> MajorCivApproach.Friendly
                else -> MajorCivApproach.Neutral
            }
            else -> approach
        }
    }

    /** CvDiplomacyAI::GetMajorCivOpinion */
    @Readonly
    fun opinionOf(other: Civilization): MajorCivOpinion = memories[other.civID]?.opinion ?: MajorCivOpinion.Neutral

    /** CvDiplomacyAI::GetMajorCivOpinionWeight of the last update (positive = bad) */
    @Readonly
    fun opinionWeightOf(other: Civilization): Int = memories[other.civID]?.opinionWeight ?: 0

    /** CvDiplomacyAI::GetWarProjection, null = NO_WAR_PROJECTION_TYPE */
    @Readonly
    fun warProjection(other: Civilization): WarProjection? = memories[other.civID]?.warProjection

    /** CvDiplomacyAI::GetWarGoal, null = NO_WAR_GOAL_TYPE */
    @Readonly
    fun warGoal(other: Civilization): WarGoal? = memories[other.civID]?.warGoal

    /** CvDiplomacyAI::GetTreatyWillingToOffer */
    @Readonly
    fun treatyWillingToOffer(other: Civilization): PeaceTreatyType = memories[other.civID]?.treatyWillingToOffer ?: PeaceTreatyType.None

    /** CvDiplomacyAI::GetTreatyWillingToAccept */
    @Readonly
    fun treatyWillingToAccept(other: Civilization): PeaceTreatyType = memories[other.civID]?.treatyWillingToAccept ?: PeaceTreatyType.None

    /** CvDiplomacyAI::GetPlayerTargetValue (kept by [WarStateAi]) */
    fun targetValue(other: Civilization): TargetValue? = WarStateAi.targetValue(civ, other)

    /** CvDiplomacyAI::GetPlayerMilitaryStrengthComparedToUs (kept by [WarStateAi]) */
    fun militaryStrength(other: Civilization): StrengthLevel? = WarStateAi.militaryStrengthComparedToUs(civ, other)

    /** CvDiplomacyAI::GetPlayerEconomicStrengthComparedToUs */
    @Readonly
    fun economicStrength(other: Civilization): StrengthLevel? = memories[other.civID]?.economicStrength

    /** CvDiplomacyAI::GetPlayerNumTurnsAtWar (kept by [WarStateAi]) */
    fun turnsAtWarWith(other: Civilization): Int = WarStateAi.turnsAtWar(civ, other)

    /** CvDiplomacyAI::GetMilitaryThreat */
    @Readonly
    fun militaryThreat(other: Civilization): AiThreat = memories[other.civID]?.militaryThreat ?: AiThreat.None

    /** CvDiplomacyAI::GetWarmongerThreat */
    @Readonly
    fun warmongerThreat(other: Civilization): AiThreat = memories[other.civID]?.warmongerThreat ?: AiThreat.None

    /** CvDiplomacyAI::GetLandDisputeLevel */
    @Readonly
    fun landDisputeLevel(other: Civilization): DisputeLevel = memories[other.civID]?.landDispute ?: DisputeLevel.None

    /** CvDiplomacyAI::GetWarDamageLevel: how much [other] has hurt us */
    @Readonly
    fun warDamageLevel(other: Civilization): WarDamageLevel = memories[other.civID]?.warDamageLevel ?: WarDamageLevel.None

    /** CvDiplomacyAI::GetTotalLandDisputeLevel: the land disputes with all players, weighted by AI_DIPLO_LAND_DISPUTE_WEIGHT_* */
    @Readonly
    fun totalLandDisputeLevel(): Int = lastTotalLandDisputeLevel

    /** CvDiplomacyAI::IsWantsPeaceWithPlayer: the war goal has been PEACE for REQUEST_PEACE_TURN_THRESHOLD turns (updated by [DiplomacyTurn]) */
    @Readonly
    fun isWantsPeaceWith(other: Civilization): Boolean = memories[other.civID]?.wantsPeace == true

    /** CvDiplomacyAI::IsWantsResearchAgreementWithPlayer (DoUpdatePlanningExchanges) */
    @Readonly
    fun wantsResearchAgreementWith(other: Civilization): Boolean = memories[other.civID]?.wantsResearchAgreement == true

    /** CvDiplomacyAI::GetNumOurEnemiesPlayerAtWarWith: how many of the valid players we fight are also at war with [other] */
    fun numOurEnemiesAtWarWith(other: Civilization): Int =
        civ.gameInfo.civilizations.count { isPlayerValid(it) && civ.isAtWarWith(it) && other.isAtWarWith(it) }

    /** CvTeam::GetTurnMadePeaceTreatyWithTeam: game turn, -1 = never */
    @Readonly
    fun turnMadePeaceWith(other: Civilization): Int = memories[other.civID]?.turnMadePeace ?: -1

    /** CvDiplomacyAI::GetGlobalCoopWarAcceptedState(ePlayer) >= COOP_WAR_STATE_SOON: a coop war with [other] as partner is agreed against anyone */
    @Readonly
    fun isCoopWarSoon(other: Civilization): Boolean {
        val wars = memories[other.civID]?.coopWars ?: return false
        for (entry in wars.values) {
            val state = entry.state
            if (state != null && state >= CoopWarState.Soon) return true
        }
        return false
    }

    /** DoContactPlayer sends one statement or offer per player per turn: true when one was sent to [other] this turn */
    @Readonly
    fun hasMadeStatementThisTurn(other: Civilization): Boolean = memories[other.civID]?.lastContactTurn == civ.gameInfo.turns

    /** Records a statement or offer to [other] this turn (see [hasMadeStatementThisTurn]) */
    fun markStatementMade(other: Civilization) {
        memoryOf(other).lastContactTurn = civ.gameInfo.turns
    }

    /** CvDiplomacyAI::ChangeRecentTradeValue: what a deal with [from] was worth to us (CvDealAI::DoAcceptedDeal: in full if generous, else half) */
    fun changeRecentTradeValue(from: Civilization, value: Int) {
        val memory = memoryOf(from)
        memory.recentTradeValue = maxOf(0, memory.recentTradeValue + value)
    }

    /** CvDiplomacyAI::ChangeRecentAssistValue: negative = [from] helped us, positive = they refused */
    fun changeRecentAssistValue(from: Civilization, value: Int) {
        memoryOf(from).recentAssistValue += value
    }

    /** Sets the approach towards [other] directly (scenario setup and tests) */
    @VisibleForTesting
    fun setApproach(other: Civilization, approach: MajorCivApproach, warFace: WarFace? = null) {
        val memory = memoryOf(other)
        memory.approach = approach
        memory.warFace = warFace
    }

    /** Sets the opinion of [other] directly (scenario setup and tests) */
    @VisibleForTesting
    fun setOpinion(other: Civilization, opinion: MajorCivOpinion) {
        memoryOf(other).opinion = opinion
    }
    //endregion
}
