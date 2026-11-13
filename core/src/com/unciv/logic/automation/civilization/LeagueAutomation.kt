package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.city.CityStats.Companion.EXCESS_FOOD_TO_PRODUCTION
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.logic.league.LeagueProposal
import com.unciv.logic.league.VoteCommitment
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * World Congress proposals, votes and vote commitments (CvLeagueAI, CvVotingClasses.cpp:7365-9860).
 * No team alliances or resurrection.
 */
object LeagueAutomation {
    /** CvLeagueAI::DoProposals and DoVotes stop after this many loops (CvVotingClasses.cpp:7449-7486) */
    const val MAX_LOOPS = 100
    /** LeagueHelpers::AI_CHOOSE_PROPOSAL_FROM_TOP (CvVotingClasses.h:77) */
    const val CHOOSE_PROPOSAL_FROM_TOP = 6
    /** CvLeague::DoProposeEnact/Repeal: an AI remembers proposals it strongly likes/dislikes when they enter the table. */
    fun recordProposal(league: League, proposal: LeagueProposal) {
        val proposer = proposal.proposer ?: return
        val resolution = league.gameInfo.ruleset.resolutions[proposal.resolution] ?: return
        val active = if (proposal.repeal) league.activeResolutions.firstOrNull { it.id == proposal.targetId } ?: return else null
        for (observer in league.gameInfo.getAliveMajorCivs()) {
            if (observer.civID == proposer || observer.isHuman() || observer.civID !in league.members) continue
            val evaluator = LeagueEvaluator(observer, league)
            val score = if (active != null) evaluator.scoreRepeal(active) else evaluator.scoreProposal(resolution, proposal.proposerChoice)
            if (score <= -46 || score >= 46) {
                val memory = league.members.getValue(observer.civID).alignmentMemory.getOrPut(proposer) { LeagueAlignmentMemory() }
                if (score >= 46) memory.likedProposalTurn = league.gameInfo.turns
                else memory.dislikedProposalTurn = league.gameInfo.turns
            }
        }
    }

    /**
     * CvLeague::FinishSession: supporters of the actual winning choice help a passed proposal or foil a failed one.
     * For a passed host election its winner also remembers the voters who elected them. A tied/no-vote
     * decision has no supporters; abstentions and votes for other choices create no memory.
     * Call before the session clears its proposals/votes, with the actual [decision] and [passed].
     */
    fun recordSessionOutcome(league: League, proposal: LeagueProposal, decision: String?, passed: Boolean) {
        if (decision == null) return
        val turn = league.gameInfo.turns
        val supporters = proposal.votes.asSequence().filter { it.delegates > 0 && it.choice == decision }.map { it.voter }.distinct()
        val proposerMemory = proposal.proposer?.let { league.members[it]?.alignmentMemory }
        for (voter in supporters) {
            if (voter !in league.members) continue
            if (proposerMemory != null) {
                val memory = proposerMemory.getOrPut(voter) { LeagueAlignmentMemory() }
                if (passed) memory.supportedProposalTurn = turn else memory.foiledProposalTurn = turn
            }
            if (passed && !proposal.repeal && league.gameInfo.ruleset.resolutions[proposal.resolution]?.isHostElection == true)
                league.members[decision]?.alignmentMemory?.getOrPut(voter) { LeagueAlignmentMemory() }?.supportedHostingTurn = turn
        }
    }

    /**
     * What a civilization does for the World Congress in its turn (CvGameLeagues::DoPlayerTurn, CvVotingClasses.cpp:6770-6825):
     * it proposes while it may, and in a session spends all its delegates, or gives them up if nothing is proposed.
     */
    fun automateLeague(civ: Civilization) {
        val league = civ.gameInfo.league ?: return
        if (!civ.isMajorCiv || !civ.isAlive()) return
        if (league.canPropose(civ)) doProposals(civ, league)
        if (league.inSession) {
            doVoteCommitments(civ, league) // Honor vote commitments made in deals before spending any other votes
            if (league.canVote(civ)) doVotes(civ, league)
        }
    }

    //region Alignment and diplomats

    /**
     * CvLeagueAI::EvaluateAlignment (CvVotingClasses.cpp:7905-8030). Works without a World Congress too (the DLL evaluates alignment for diplomats
     * before there is one), then without the Congress memories.
     */
    fun evaluateAlignment(civ: Civilization, other: Civilization): LeagueAlignment {
        if (!other.isAlive() || !other.isMajorCiv) return LeagueAlignment.NEUTRAL
        if (other == civ) return LeagueAlignment.SELF
        if (civ.isAtWarWith(other)) return LeagueAlignment.WAR

        var sum = 0
        val ours = civ.getIdeology()
        val theirs = other.getIdeology()
        val memory = civ.gameInfo.league?.members?.get(civ.civID)?.alignmentMemory?.get(other.civID)
        if (memory != null) {
            val turn = civ.gameInfo.turns
            if (memory.supportedHostingTurn.isRecentAt(turn)) sum += 2
            if ((memory.likedProposalTurn.isRecentAt(turn) && memory.likedProposalTurn > memory.dislikedProposalTurn) ||
                (memory.supportedProposalTurn.isRecentAt(turn) && memory.supportedProposalTurn > memory.foiledProposalTurn)) sum += 2
            if ((memory.dislikedProposalTurn.isRecentAt(turn) && memory.dislikedProposalTurn > memory.likedProposalTurn) ||
                (memory.foiledProposalTurn.isRecentAt(turn) && memory.foiledProposalTurn > memory.supportedProposalTurn)) sum -= 2
        }
        if (ours != null && theirs != null) sum += if (ours.name == theirs.name) 2 else -2
        val diplomacy = civ.getDiplomacyManager(other)
        if (diplomacy != null) {
            if (diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) sum += 1
            if (diplomacy.hasFlag(DiplomacyFlags.Denunciation)) sum -= 1
            val level = diplomacy.relationshipIgnoreAfraid()
            if (level > RelationshipLevel.Neutral) sum += 1
            else if (level <= RelationshipLevel.Enemy) sum -= 1
        }
        return when {
            sum < -6 -> LeagueAlignment.ENEMY
            sum < -4 -> LeagueAlignment.HATRED
            sum < -1 -> LeagueAlignment.RIVAL
            sum <= 1 -> LeagueAlignment.NEUTRAL
            sum <= 4 -> LeagueAlignment.FRIEND
            sum <= 6 -> LeagueAlignment.CONFIDANT
            else -> LeagueAlignment.ALLY
        }
    }

    private fun Int.isRecentAt(turn: Int) = this >= 0 && turn >= this && turn - this < 45

    /**
     * CvLeagueAI::GetDiplomatUsefulnessAtCiv (CvVotingClasses.cpp:8242-8266): +1 if our diplomats give delegates (Globalization,
     * GetExtraVotesPerDiplomat), +1 if [other] is more than neutrally aligned with us. HIGH is never returned, as in the DLL.
     */
    fun getDiplomatUsefulnessAtCiv(civ: Civilization, other: Civilization): DiplomatUsefulness {
        var score = 0
        if (civ.tech.isResearched(League.GLOBALIZATION)) score++
        if (evaluateAlignment(civ, other) > LeagueAlignment.NEUTRAL) score++
        return when {
            score >= 2 -> DiplomatUsefulness.MEDIUM
            score >= 1 -> DiplomatUsefulness.LOW
            else -> DiplomatUsefulness.NONE
        }
    }

    //endregion
    //region Vote commitments

    /**
     * CvLeagueAI::GetDesiredVoteCommitments (CvVotingClasses.cpp:7488-7643): the commitment [civ] would like [fromCiv] to make to it, one per proposal.
     * For an AI the choice it desires most, if more than neutrally; for a human the obvious ones (yes on its own proposals, no on an embargo on it,
     * itself as host or World Leader). The votes are the core votes of [fromCiv].
     */
    fun desiredVoteCommitments(civ: Civilization, fromCiv: Civilization): List<VoteCommitment> {
        val league = civ.gameInfo.league ?: return emptyList()
        val resolutions = civ.gameInfo.ruleset.resolutions
        val votes = league.getCoreVotes(fromCiv)
        val desired = ArrayList<VoteCommitment>()
        for (proposal in league.proposals) {
            if (proposal.onHold) continue
            val resolution = resolutions[proposal.resolution] ?: continue
            var choice: String? = null
            if (civ.isHuman()) {
                if (proposal.repeal) {
                    if (proposal.proposer == civ.civID) choice = League.YES
                    if (resolution.embargoesPlayer && proposal.proposerChoice == civ.civID) choice = League.YES
                } else if (resolution.voterDecision == ResolutionDecision.YesNo) {
                    if (proposal.proposer == civ.civID) choice = League.YES
                    if (resolution.embargoesPlayer && proposal.proposerChoice == civ.civID) choice = League.NO
                } else if (resolution.isHostElection || resolution.isWorldLeader) choice = civ.civID
            } else {
                var highest = LeagueDesire.NEUTRAL
                val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution.voterDecision
                for (option in league.getChoices(decision, fromCiv)) {
                    val desire = evaluateVoteForTrade(civ, proposal.id, option, votes, proposal.repeal)
                    if (desire > highest) {
                        choice = option
                        highest = desire
                    }
                }
            }
            if (choice != null) desired.add(VoteCommitment(civ.civID, proposal.id, choice, votes, !proposal.repeal))
        }
        return desired
    }

    /**
     * CvLeagueAI::EvaluateVoteForTrade (CvVotingClasses.cpp:7819-7863): how much [civ] likes [choice] on the proposal [proposalId] (an enactment,
     * or a repeal if [repeal]), whoever casts the votes. NEVER if the proposal is not on the table.
     */
    fun evaluateVoteForTrade(civ: Civilization, proposalId: Int, choice: String, @Suppress("UNUSED_PARAMETER") votes: Int, repeal: Boolean): LeagueDesire {
        val league = civ.gameInfo.league ?: return LeagueDesire.NEVER
        val proposal = league.proposals.firstOrNull { it.id == proposalId && it.repeal == repeal && !it.onHold } ?: return LeagueDesire.NEVER
        return LeagueDesire.of(LeagueEvaluator(civ, league).scoreVote(proposal, choice))
    }

    /**
     * CvLeagueAI::CanCommitVote (CvVotingClasses.cpp:7665-7752): whether [toCiv] can secure the votes of [civ] in a deal: there is a World Congress,
     * it is not in session, [toCiv] has a diplomat in our capital, we are a voting member, something is proposed and we have committed nothing yet.
     */
    fun canCommitVote(civ: Civilization, toCiv: Civilization): Boolean {
        val league = civ.gameInfo.league ?: return false
        if (league.inSession) return false
        if (toCiv.espionageManager.getActiveDiplomatFor(civ) == null) return false
        val member = league.members[civ.civID] ?: return false
        if (!civ.isMajorCiv || !civ.isAlive()) return false
        if (league.proposals.none { !it.onHold }) return false
        return member.voteCommitments.isEmpty()
    }

    /**
     * CvDeal::IsPossibleToTradeItem for TRADE_ITEM_VOTE_COMMITMENT: [civ] promises [toCiv] [votes] votes on [choice] of the proposal [proposalId]
     * (an enactment, or a repeal if [repeal]). Not while at war; [canCommitVote]; the proposal must be on the table (not on hold) and at least one vote and
     * at most the core votes of [civ] promised. The original checks the choice nowhere (a todo) and only asserts the votes.
     */
    fun isVoteCommitmentPossible(civ: Civilization, toCiv: Civilization, proposalId: Int, choice: String, votes: Int, repeal: Boolean): Boolean {
        if (civ.isAtWarWith(toCiv) || !canCommitVote(civ, toCiv)) return false
        val league = civ.gameInfo.league ?: return false
        if (votes < 1 || votes > league.getCoreVotes(civ)) return false
        val proposal = league.proposals.firstOrNull { it.id == proposalId && it.repeal == repeal && !it.onHold } ?: return false
        return choice in choicesOf(league, proposal, civ)
    }

    /** What the voters of [proposal] choose from: yes or no for a repeal */
    private fun choicesOf(league: League, proposal: LeagueProposal, civ: Civilization): List<String> {
        val decision = if (proposal.repeal) ResolutionDecision.YesNo else league.gameInfo.ruleset.resolutions[proposal.resolution]?.voterDecision
        return if (decision == null) emptyList() else league.getChoices(decision, civ)
    }

    /**
     * The vote commitments [civ] could make to [toCiv] in a deal, which the trade screen offers: all its core votes on each choice of each proposal on
     * the table, if [isVoteCommitmentPossible] allows any. (The original's Lua offers the same items.)
     */
    fun availableVoteCommitments(civ: Civilization, toCiv: Civilization): List<VoteCommitment> {
        val league = civ.gameInfo.league ?: return emptyList()
        if (civ.isAtWarWith(toCiv) || !canCommitVote(civ, toCiv)) return emptyList()
        val votes = league.getCoreVotes(civ)
        if (votes < 1) return emptyList()
        return league.proposals.filter { !it.onHold }.flatMap { proposal ->
            choicesOf(league, proposal, civ).map { VoteCommitment(toCiv.civID, proposal.id, it, votes, !proposal.repeal) }
        }
    }

    /** CvLeagueAI::AddVoteCommitment: a deal secured the votes of [civ] for [toCiv] */
    fun addVoteCommitment(civ: Civilization, toCiv: Civilization, proposalId: Int, choice: String, votes: Int, repeal: Boolean) {
        val member = civ.gameInfo.league?.members?.get(civ.civID) ?: return
        member.voteCommitments.add(VoteCommitment(toCiv.civID, proposalId, choice, votes, !repeal))
    }

    /** CvLeagueAI::CancelVoteCommitmentsToPlayer: the deal that made them ended (war, death) */
    fun cancelVoteCommitmentsToPlayer(civ: Civilization, toCiv: Civilization) {
        civ.gameInfo.league?.members?.get(civ.civID)?.voteCommitments?.removeAll { it.toCiv == toCiv.civID }
    }

    /**
     * CvLeagueAI::DoVoteCommitments (CvVotingClasses.cpp:7775-7816): votes as promised on the proposals on the table; keeps a commitment on a proposal
     * that is on hold, drops one whose proposal is gone. Humans' commitments are honored too.
     */
    fun doVoteCommitments(civ: Civilization, league: League) {
        val member = league.members[civ.civID] ?: return
        val iterator = member.voteCommitments.iterator()
        while (iterator.hasNext()) {
            val commitment = iterator.next()
            if (!league.canVote(civ)) continue
            val proposal = league.proposals.firstOrNull { it.id == commitment.proposalId && it.repeal == !commitment.enact }
            if (proposal != null && proposal.onHold) continue
            if (proposal != null) {
                val resolution = league.gameInfo.ruleset.resolutions[proposal.resolution]
                val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution?.voterDecision
                val votes = minOf(commitment.votes, league.getDelegatesLeft(civ))
                // A choice that is no longer valid (the civilization voted for died) is dropped like a proposal that is gone
                if (votes > 0 && decision != null && commitment.choice in league.getChoices(decision, civ))
                    league.vote(proposal.id, civ, votes, commitment.choice)
            }
            iterator.remove()
        }
    }

    //endregion

    //region Proposals

    private class ProposalConsideration(val resolution: Resolution?, val choice: String?, val repeal: ActiveResolution?)

    /** CvLeagueAI::DoProposals: one proposal per privilege (CvVotingClasses.cpp:7473-7486) */
    private fun doProposals(civ: Civilization, league: League) {
        val evaluator = LeagueEvaluator(civ, league)
        var loops = 0
        while (league.canPropose(civ) && loops++ < MAX_LOOPS) {
            val considerations = ArrayList<ProposalConsideration>()
            val weights = ArrayList<Int>()
            for (active in league.activeResolutions.toList()) {
                if (!league.canProposeRepeal(active.id, civ)) continue
                considerations.add(ProposalConsideration(null, null, active))
                weights.add(evaluator.scoreRepeal(active))
            }
            for (resolution in league.gameInfo.ruleset.resolutions.values) {
                val choices: List<String?> =
                    if (resolution.proposerDecision == ResolutionDecision.None) listOf(null)
                    else league.getChoices(resolution, civ)
                for (choice in choices) {
                    if (!league.canProposeEnact(resolution, choice, civ)) continue
                    considerations.add(ProposalConsideration(resolution, choice, null))
                    weights.add(evaluator.scoreProposal(resolution, choice))
                }
            }
            if (considerations.isEmpty()) return // nothing to propose, League.removeUselessPrivileges takes the right away next turn

            val rng = civ.state.stateBasedRandom("LeagueAutomation.propose", league.gameInfo.turns * 31 + league.nextProposalId)
            val chosen = chooseProposal(considerations, weights, rng)
            if (chosen.repeal != null) league.proposeRepeal(chosen.repeal.id, civ)
            else league.proposeEnact(chosen.resolution!!, chosen.choice, civ)
        }
    }

    /**
     * CvLeagueAI::AllocateProposals (CvVotingClasses.cpp:9602-9686): descending by weight, a weighted roll among the best [CHOOSE_PROPOSAL_FROM_TOP].
     * If the weights add up to nothing positive all count as 1. Deviation: the original rolls with negative weights among the top, which is undefined
     * there (CvWeightedVector.h:222-260); here they count as 0.
     */
    private fun chooseProposal(considerations: List<ProposalConsideration>, rawWeights: List<Int>, rng: Random): ProposalConsideration {
        val weights = if (rawWeights.sum() <= 0) rawWeights.map { 1 } else rawWeights
        val order = considerations.indices.sortedByDescending { weights[it] }.take(CHOOSE_PROPOSAL_FROM_TOP)
        val topWeights = order.map { max(0, weights[it]) }
        return considerations[order[rollIndex(topWeights, rng)]]
    }

    /** The index a roll up to the total weight lands on; all equal if nothing is positive (CvWeightedVector::ChooseFromTopChoices) */
    private fun rollIndex(weights: List<Int>, rng: Random): Int {
        val total = weights.sum()
        if (total <= 0) return rng.nextInt(weights.size)
        var roll = rng.nextInt(total)
        for (index in weights.indices) {
            roll -= weights[index]
            if (roll < 0) return index
        }
        return weights.lastIndex
    }

    //endregion
    //region Votes

    private class VoteConsideration(val proposal: LeagueProposal, val choice: String, var weight: Int)

    /** CvLeagueAI::DoVotes and AllocateVotes (CvVotingClasses.cpp:7449-7466, 8453-8590) */
    private fun doVotes(civ: Civilization, league: League) {
        val onTable = league.proposals.filter { !it.onHold }
        if (onTable.isEmpty()) {
            league.abstainRemaining(civ)
            return
        }
        val evaluator = LeagueEvaluator(civ, league)
        val considerations = ArrayList<VoteConsideration>()
        for (proposal in onTable) {
            val (choice, score) = evaluator.findBestVoteChoice(proposal) ?: continue
            considerations.add(VoteConsideration(proposal, choice, max(score, 0))) // no negative weights
        }
        if (considerations.isEmpty()) {
            league.abstainRemaining(civ)
            return
        }

        // If an embargo on us is proposed (or its repeal), everything goes to it
        val focus = onTable.firstOrNull { proposal ->
            league.gameInfo.ruleset.resolutions[proposal.resolution]?.embargoesPlayer == true && proposal.proposerChoice == civ.civID
        }
        if (focus != null) for (consideration in considerations) if (consideration.proposal != focus) consideration.weight = 0
        if (considerations.sumOf { it.weight } <= 0) for (consideration in considerations) consideration.weight = 1

        // One delegate at a time, each rolled by weight; the votes of one proposal are cast together (the League merges them anyway)
        val rng = civ.state.stateBasedRandom("LeagueAutomation.vote", league.gameInfo.turns)
        val weights = considerations.map { it.weight }
        val counts = IntArray(considerations.size)
        repeat(league.getDelegatesLeft(civ)) { counts[rollIndex(weights, rng)]++ }
        for ((index, consideration) in considerations.withIndex())
            if (counts[index] > 0) league.vote(consideration.proposal.id, civ, counts[index], consideration.choice)
        if (league.canVote(civ)) league.abstainRemaining(civ)
    }

    //endregion
}

/** How the civilization sees another one in the World Congress (CvLeagueAI::AlignmentLevels, CvVotingClasses.h:786-799) */
enum class LeagueAlignment { WAR, ENEMY, HATRED, RIVAL, NEUTRAL, FRIEND, CONFIDANT, ALLY, SELF, LEADER, LIBERATOR }

/** How much the civilization likes a choice (CvLeagueAI::DesireLevels, CvVotingClasses.h:759-771) */
enum class LeagueDesire {
    NEVER, STRONG_DISLIKE, DISLIKE, WEAK_DISLIKE, NEUTRAL, WEAK_LIKE, LIKE, STRONG_LIKE, ALWAYS;

    companion object {
        /** CvLeagueAI::EvaluateDesire (CvVotingClasses.cpp:8408-8450) */
        fun of(rawScore: Int): LeagueDesire = when {
            rawScore < -105 -> NEVER
            rawScore < -75 -> STRONG_DISLIKE
            rawScore < -45 -> DISLIKE
            rawScore < -15 -> WEAK_DISLIKE
            rawScore <= 15 -> NEUTRAL
            rawScore <= 45 -> WEAK_LIKE
            rawScore <= 75 -> LIKE
            rawScore <= 105 -> STRONG_LIKE
            else -> ALWAYS
        }
    }
}

/** Where a diplomat helps in the World Congress (CvLeagueAI::DiplomatUsefulnessLevels) */
enum class DiplomatUsefulness { NONE, LOW, MEDIUM, HIGH }

/**
 * Scores what [civ] likes in the World Congress (CvLeagueAI::ScoreVoteChoice and friends, CvVotingClasses.cpp:7905-8030, 8759-9600).
 * Positive is like. The scores are the integers of the original.
 *
 * ## Mapping layer: inputs of the original and what stands in for them
 * - Grand strategy (`bSeeking*`): CvDiplomacyAI::IsGoingFor* of the active grand strategy ([com.unciv.logic.automation.strategy.CivAi]):
 *   Conquest, United Nations (diplomacy), Spaceship (space race), Culture. Before the first grand strategy is chosen none of them is sought.
 * - Production might: the production of the cities without what excess food adds (CvPlayer::calculateProductionMight, getRawProductionDifference
 *   with bIgnoreFood). Tech rank: the number of researched technologies (CvTechAI::GetTechRatio, position among the living major civilizations,
 *   ties by civilization order).
 * - Alignment: [LeagueAutomation.evaluateAlignment].
 * - Opinion: [DiplomacyManager.relationshipIgnoreAfraid]; "opinion above neutral" is Favorable or better, "at most enemy" Enemy or Unforgivable,
 *   "below neutral" Competitor or worse. Approach "hostile or worse" is a denunciation or an Enemy level, approach "afraid or friendly" has no equivalent.
 * - Warmonger threat at least severe: CvDiplomacyAI::GetWarmongerThreat, [com.unciv.logic.automation.diplomacy.DiplomacyAi.warmongerThreat].
 * - Trade connections: routes between the two civilizations in either direction. Morocco's trait (incoming route yield): [UniqueType.TradeRouteGoldForOrigin],
 *   Venice's (more routes): a positive [UniqueType.TradeRouteSlotsPercent]. Possible routes: [com.unciv.logic.civilization.managers.TradeRouteManager.getNumSlots].
 * - Gross gold and unit maintenance: the gold entries of the stat map of the next turn, "Unit upkeep" being the maintenance.
 * - Wonders: the world wonders in the cities. Natural wonders and resources "in owned plots": the tiles of the cities. Empire unhappy: happiness below 0.
 * - Great person improvements: tiles with a great improvement; landmarks: tiles with the improvement [LANDMARK].
 * - Sciencey unique unit (Merchant of Venice): the civilization's equivalent of [GREAT_MERCHANT] is another unit.
 * - Resolutions are found by effect (uniques and typed fields), except Nuclear Non-Proliferation and the projects, which have no effect field: by name.
 *
 * ## Gaps
 * Teams (no LEADER or teammate ALLY), resurrection (LIBERATOR), diplomat knowledge,
 * the approach of the diplomacy AI, resources of civilizations met only through trade.
 * Congress proposal/host memories are saved with members and expire after 45 game turns.
 *
 * Deviations: the choice for a tie between equal best choices is No for yes/no (the status quo) and the civilization itself for civilization votes,
 * the original leaves it to an unstable sort. A division by zero when the gross gold is 0 (CvVotingClasses.cpp:9147) counts as ratio 0.
 */
class LeagueEvaluator(private val civ: Civilization, private val league: League) {
    companion object {
        const val LANDMARK = "Landmark"
        const val GREAT_MERCHANT = "Great Merchant"
        /** The resolutions that start an international project, with the "culture" or "science" strategy that profits (CvVotingClasses.cpp:8866-8898) */
        private val projects = mapOf("World's Fair" to (30 to CULTURE), "International Games" to (40 to CULTURE),
            "International Space Station" to (50 to SCIENCE))
        private const val NUCLEAR_NON_PROLIFERATION = "Nuclear Non-Proliferation"
        private val artsyPersons = setOf("Great Artist", "Great Writer", "Great Musician")
        private val scienceyPersons = setOf("Great Scientist", "Great Engineer", "Great Merchant")

        /** The strategy (seeking culture or science) a project profits */
        private const val SCIENCE = "Scientific"
        private const val CULTURE = "Cultural"
    }

    private val gameInfo = league.gameInfo
    private val seekingConquest = civ.ai.isGoingForWorldConquest()
    private val seekingDiplomacy = civ.ai.isGoingForDiploVictory()
    private val seekingScience = civ.ai.isGoingForSpaceshipVictory()
    private val seekingCulture = civ.ai.isGoingForCultureVictory()

    private val ownedTiles by lazy { civ.cities.flatMap { it.getTiles().toList() } }
    private val statMap by lazy { civ.stats.getStatMapForNextTurn() }

    //region Entry points

    /** CvLeagueAI::ScoreProposal for an enactment: our YES on a proposal without proposer, so the alignment with the proposer does not count (CvVotingClasses.cpp:9688-9713) */
    fun scoreProposal(resolution: Resolution, choice: String?): Int = scoreYesNoEnact(resolution, choice, null)

    /** CvLeagueAI::ScoreProposal for a repeal: our YES on repealing, which is the effects score reversed (CvVotingClasses.cpp:9715-9740) */
    fun scoreRepeal(active: ActiveResolution): Int {
        val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return 0
        return -scoreYesNoEnact(resolution, active.choice, null)
    }

    /**
     * CvLeagueAI::ScoreVoteChoice: how much we like [choice] on [proposal] (YES/NO, or the id of a civilization). For a vote on a civilization (host, World Leader)
     * the proposal's proposer does not matter, for a yes/no vote his alignment does.
     */
    fun scoreVote(proposal: LeagueProposal, choice: String): Int {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return 0
        if (!proposal.repeal && resolution.voterDecision == ResolutionDecision.MajorCiv) {
            val candidate = gameInfo.getCivilizationOrNull(choice) ?: return 0
            return scorePlayerChoice(resolution, candidate)
        }
        var score = scoreYesNoEnact(resolution, proposal.proposerChoice, proposal.proposer)
        if (choice == League.NO) score = -score
        if (proposal.repeal) score = -score
        return score
    }

    /** CvLeagueAI::FindBestVoteChoices: the best of the choices with its raw score, `null` if there is none (CvVotingClasses.cpp:8593-8689) */
    fun findBestVoteChoice(proposal: LeagueProposal): Pair<String, Int>? {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return null
        val decision = if (proposal.repeal) ResolutionDecision.YesNo else resolution.voterDecision
        var choices = league.getChoices(decision, civ)
        // ties go to the status quo (No) or to ourselves, see the class comment
        choices = if (decision == ResolutionDecision.YesNo) choices.reversed() else choices.sortedBy { it != civ.civID }
        var best: Pair<String, Int>? = null
        for (choice in choices) {
            val score = scoreVote(proposal, choice)
            if (best == null || score > best.second) best = choice to score
        }
        return best
    }

    //endregion
    //region Choosing a civilization

    /** CvLeagueAI::ScoreVoteChoicePlayer (CvVotingClasses.cpp:9463-9600): World Leader and Choose Host */
    fun scorePlayerChoice(resolution: Resolution, candidate: Civilization): Int {
        var score = 0
        val alignment = LeagueAutomation.evaluateAlignment(civ, candidate)
        if (resolution.isWorldLeader) {
            when (alignment) {
                LeagueAlignment.LIBERATOR -> score += 200
                LeagueAlignment.LEADER -> score += 150
                LeagueAlignment.SELF -> score += 100
                LeagueAlignment.WAR -> score -= 500
                else -> {
                    score -= 50
                    if (seekingDiplomacy) score -= 150
                    // Don't hand the victory to someone
                    if (league.getDelegates(civ) + league.getDelegates(candidate) + 4 >= league.getVotesNeededForWorldLeader()) score -= 150
                    score += when (alignment) {
                        LeagueAlignment.ALLY -> 70
                        LeagueAlignment.CONFIDANT -> 50
                        LeagueAlignment.FRIEND -> 30
                        LeagueAlignment.RIVAL -> -30
                        LeagueAlignment.HATRED -> -50
                        LeagueAlignment.ENEMY -> -70
                        else -> 0
                    }
                }
            }
        }
        if (resolution.isHostElection) {
            when (alignment) {
                LeagueAlignment.LIBERATOR -> score += 200
                LeagueAlignment.SELF -> score += 100
                LeagueAlignment.LEADER -> score += 50
                LeagueAlignment.WAR -> score -= 500
                else -> {
                    if (seekingDiplomacy) score -= 150
                    score += when (alignment) {
                        LeagueAlignment.ALLY -> 130
                        LeagueAlignment.CONFIDANT -> 90
                        LeagueAlignment.FRIEND -> 50
                        LeagueAlignment.RIVAL -> -50
                        LeagueAlignment.HATRED -> -90
                        LeagueAlignment.ENEMY -> -130
                        else -> 0
                    }
                }
            }
        }
        return score
    }

    //endregion
    //region Yes/No resolutions

    /**
     * CvLeagueAI::ScoreVoteChoiceYesNo (CvVotingClasses.cpp:8759-9460) for our YES on enacting [resolution] with the proposer's [choice]:
     * the effects, then the alignment with [proposerId] (only if what we have so far is above -20, or we are the proposer).
     */
    private fun scoreYesNoEnact(resolution: Resolution, choice: String?, proposerId: String?): Int {
        var score = scoreEffects(resolution, choice)
        if (proposerId != null && (score > -20 || proposerId == civ.civID)) {
            val proposer = gameInfo.getCivilizationOrNull(proposerId)
            if (proposer != null) score += when (LeagueAutomation.evaluateAlignment(civ, proposer)) {
                LeagueAlignment.SELF -> 40
                LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER, LeagueAlignment.ALLY -> 30
                LeagueAlignment.CONFIDANT -> 20
                LeagueAlignment.FRIEND -> 10
                LeagueAlignment.RIVAL -> -10
                LeagueAlignment.HATRED -> -20
                LeagueAlignment.ENEMY, LeagueAlignment.WAR -> -30
                else -> 0
            }
        }
        return score
    }

    /** The effects part of ScoreVoteChoiceYesNo, in the order of the original, which matters for the caps and the hard set to 0 */
    fun scoreEffects(resolution: Resolution, choice: String?): Int {
        var score = 0

        if (resolution.isProject) score += scoreProject(resolution.name)

        if (resolution.embargoesCityStates) score += scoreEmbargoCityStates()
        if (resolution.embargoesPlayer) score += scoreEmbargoPlayer(choice)
        if (resolution.bansLuxuryHappiness) score = scoreBanLuxury(score, choice)

        val maintenancePercent = resolution.getMatchingUniques(UniqueType.UnitGoldMaintenancePercent).sumOf { it.params[0].toInt() }
        if (maintenancePercent != 0) score += scoreStandingArmyTax(if (maintenancePercent > 0) 1 else -1)

        if (resolution.hasUnique(UniqueType.TechKnownByAnyCivResearchSpeed)) score += scoreScholars()

        val cultureFrom = resolution.getMatchingUniques(UniqueType.StatsFromObject)
            .filter { it.stats[Stat.Culture] != 0f }.map { it.params[1] }.toSet()
        if ("World Wonder" in cultureFrom) {
            var temp = -50
            val wonders = civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { it.isWonder } }
            if (wonders > 0) temp += wonders * (if (seekingCulture) 20 else 15)
            score = min(70, score + temp)
        }
        if ("Natural Wonder" in cultureFrom) {
            var temp = -35
            val wonders = ownedTiles.count { it.isNaturalWonder() }
            if (wonders > 0) temp += wonders * (if (seekingCulture) 20 else 15)
            score = min(70, score + temp)
        }

        if (resolution.name == NUCLEAR_NON_PROLIFERATION) score += if (seekingConquest) -50 else 35

        if (resolution.votesForFollowingReligion != 0 || resolution.holyCityTourismPercent != 0 || resolution.religionSpreadStrengthPercent != 0)
            score += scoreWorldReligion(choice)
        if (resolution.votesForFollowingIdeology != 0 || resolution.ideologyPressure != 0)
            score += scoreWorldIdeology(choice)

        val greatPersonRates = resolution.getMatchingUniques(UniqueType.GreatPersonEarnedFaster)
        val artsy = greatPersonRates.filter { it.params[0] in artsyPersons }.sumOf { it.params[1].toInt() }
        val sciencey = greatPersonRates.filter { it.params[0] in scienceyPersons }.sumOf { it.params[1].toInt() }
        if (artsy > 0 || sciencey < 0) {
            if (seekingCulture) score += 80 else if (seekingScience) score -= 80
            if (hasScienceyUniqueUnit()) score -= 60
        }
        if (sciencey > 0 || artsy < 0) {
            if (seekingScience) score += 80 else if (seekingCulture) score -= 80
            if (hasScienceyUniqueUnit()) score += 60
        }

        if ("Great Improvement" in cultureFrom || LANDMARK in cultureFrom) {
            val improvements = gameInfo.ruleset.tileImprovements
            val greatImprovements = ownedTiles.count { tile -> tile.improvement?.let { improvements[it]?.isGreatImprovement() } == true }
            val landmarks = ownedTiles.count { it.improvement == LANDMARK }
            var temp = -50
            if (greatImprovements > 0 || landmarks > 0) {
                val factor = if (seekingCulture) 20 else 15
                temp += greatImprovements * factor + landmarks * factor
                score += min(70, temp)
            }
            score += temp // the original counts it twice (CvVotingClasses.cpp:9395-9406)
        }
        return score
    }

    /**
     * International projects (CvVotingClasses.cpp:8806-8898): the production rank decides (+40/+20/-20/-40), then the project: +30 World's Fair or
     * +40 International Games when seeking culture, +50 International Space Station when seeking science, with strong production; else +10.
     * A project the DLL does not know gets the production part only.
     */
    private fun scoreProject(projectName: String): Int {
        val alive = gameInfo.getAliveMajorCivs()
        var score = 0
        var strong = false
        if (alive.isNotEmpty()) {
            val ours = productionMight(civ)
            val higher = alive.count { it != civ && productionMight(it) > ours }
            val ratio = ((alive.size - higher).toFloat() / alive.size).coerceIn(0f, 1f)
            when {
                ratio >= 0.75f -> { score += 40; strong = true }
                ratio >= 0.5f -> { score += 20; strong = true }
                ratio >= 0.25f -> score -= 20
                else -> score -= 40
            }
        }
        val project = projects[projectName] ?: return score
        val seeking = if (project.second == CULTURE) seekingCulture else seekingScience
        score += if (seeking && strong) project.first else 10
        return score
    }

    /** CvPlayer::calculateProductionMight: the sum over the cities of their production without the excess food a settler turns into production */
    private fun productionMight(c: Civilization) = c.cities.sumOf { city ->
        val fromFood = city.cityStats.finalStatList[EXCESS_FOOD_TO_PRODUCTION]?.production ?: 0f
        (city.cityStats.currentCityStats.production - fromFood).toInt()
    }

    /** (CvVotingClasses.cpp:8901-8973) */
    private fun scoreEmbargoCityStates(): Int {
        var partners = 0
        var civDestinations = 0
        for (other in gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
            if (other.isCityState) {
                if (routesBetween(civ, other) > 0) partners++
            } else if (!league.isTradeEmbargoed(civ, other)) civDestinations += other.cities.size
        }
        var score = if (partners > 0) max(-50, partners * -15) else 25
        if (civDestinations <= 0) score -= 50
        else if (civDestinations * civ.cities.size / 3 < civ.tradeRoutes.getNumSlots()) score -= 40
        if (hasIncomingRouteTrait()) score -= 40
        if (hasMoreRoutesTrait()) score -= 20
        return score
    }

    /** (CvVotingClasses.cpp:8975-9033) */
    private fun scoreEmbargoPlayer(choice: String?): Int {
        val target = choice?.let { gameInfo.getCivilizationOrNull(it) } ?: return 0
        if (target == civ) return -100
        if (!target.isMajorCiv) return 0
        val diplomacy = civ.getDiplomacyManager(target)
        var score = when {
            civ.isAtWarWith(target) -> 70
            civ.ai.diplomacy.warmongerThreat(target) >= AiThreat.Severe -> 40
            diplomacy != null && (diplomacy.relationshipIgnoreAfraid() < RelationshipLevel.Neutral
                || diplomacy.relationshipIgnoreAfraid() <= RelationshipLevel.Enemy
                || diplomacy.hasFlag(DiplomacyFlags.Denunciation)) -> 20
            else -> -30
        }
        val routes = routesBetween(civ, target)
        if (routes > 0) score += max(-40, routes * -10)
        if (hasIncomingRouteTrait()) score -= 20
        if (hasMoreRoutesTrait()) score -= 20
        return score
    }

    /** (CvVotingClasses.cpp:9035-9134); [running] is the score so far, which is replaced by 0 if nobody owns the luxury */
    private fun scoreBanLuxury(running: Int, luxury: String?): Int {
        if (luxury == null) return running
        var score = running
        var factor = 0
        var ownedByAnyone = false
        for (other in gameInfo.getAliveMajorCivs()) {
            if (other == civ || !civ.knows(other)) continue
            if (other.getResourceAmount(luxury) <= 0) continue
            ownedByAnyone = true
            val diplomacy = civ.getDiplomacyManager(other)
            val level = diplomacy?.relationshipIgnoreAfraid() ?: RelationshipLevel.Neutral
            factor += when {
                civ.isAtWarWith(other) -> -3
                diplomacy?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true -> 3
                level == RelationshipLevel.Competitor -> -1
                level < RelationshipLevel.Competitor -> -2
                level == RelationshipLevel.Favorable -> 1
                level > RelationshipLevel.Favorable -> 2
                else -> 0
            }
        }
        if (factor > 0) { score -= 10; if (factor > 3) score -= 10 }
        else if (factor < 0) { score += 10; if (factor < -3) score += 10 }

        val inPlots = ownedTiles.count { it.resource == luxury }
        if (civ.getResourceAmount(luxury) > 0) {
            ownedByAnyone = true
            score -= 30
            if (inPlots > 0) score -= 20
            if (civ.stats.happiness < 0) score -= 20
        } else score += if (inPlots > 0) -20 else 15
        return if (ownedByAnyone) score else 0
    }

    /** (CvVotingClasses.cpp:9136-9164); [factor] is 1 for more maintenance, -1 for less */
    private fun scoreStandingArmyTax(factor: Int): Int {
        var score = if (seekingConquest) -40 * factor else 0
        val maintenance = -(statMap["Unit upkeep"]?.gold ?: 0f)
        val gross = statMap.values.sumOf { max(it.gold, 0f).toDouble() }.toFloat()
        val ratio = if (gross > 0f) maintenance / gross else 0f
        score += when {
            gross - maintenance < 0 -> -50 * factor
            ratio >= 0.5f -> -40 * factor
            ratio >= 0.2f -> -15 * factor
            else -> 15 * factor
        }
        return score
    }

    /** (CvVotingClasses.cpp:9166-9191) */
    private fun scoreScholars(): Int {
        val ratio = (TechAutomation.techRatio(civ) - 0.5f) * 2f // -1 if in first, 1 if in last
        return if (ratio < 0f) (ratio * 30).toInt() + (if (seekingScience) -30 else 0)
        else (ratio * 50).toInt() + (if (seekingScience) 40 else 0)
    }

    /** (CvVotingClasses.cpp:9231-9278) */
    private fun scoreWorldReligion(choice: String?): Int {
        val religion = choice?.let { gameInfo.religions[it] } ?: return 0
        var score = 0
        if (civ.religionManager.isMajorityReligionForCiv(religion)) {
            score += 40
            if (seekingDiplomacy) score += 20
        } else score -= 30
        if (civ.cities.any { it.isHolyCityOf(religion.name) }) {
            score += 10
            if (seekingCulture) score += 30
        }
        val founded = civ.religionManager.religion?.takeIf { it.isMajorReligion() && it.foundingCiv == civ }
        if (founded?.name == religion.name) score += 40
        else if (founded != null) score -= 20
        return score
    }

    /** (CvVotingClasses.cpp:9280-9313) */
    private fun scoreWorldIdeology(choice: String?): Int {
        val ours = civ.getIdeology() ?: return 0
        var score = 0
        val unhappy = civ.culture.publicOpinion.unhappiness > 0
        val sign = if (ours.name == choice) 1 else -1
        score += 50 * sign
        if (seekingDiplomacy) score += 25 * sign
        if (unhappy) score += 25 * sign
        return score
    }

    private fun hasScienceyUniqueUnit(): Boolean =
        GREAT_MERCHANT in gameInfo.ruleset.units && civ.getEquivalentUnit(GREAT_MERCHANT).name != GREAT_MERCHANT

    /** Morocco: yield for incoming trade routes (YieldChangeIncomingTradeRoute) */
    private fun hasIncomingRouteTrait() = hasPositiveUnique(UniqueType.TradeRouteGoldForOrigin)

    /** Venice: more trade routes (NumTradeRoutesModifier) */
    private fun hasMoreRoutesTrait() = hasPositiveUnique(UniqueType.TradeRouteSlotsPercent)

    private fun hasPositiveUnique(type: UniqueType): Boolean {
        var found = false
        civ.forEachMatchingUnique(type) { if (it.params[0].toInt() > 0) found = true }
        return found
    }

    private fun routesBetween(a: Civilization, b: Civilization): Int =
        a.tradeRoutes.routes.count { it.getDestinationCiv() == b } + b.tradeRoutes.routes.count { it.getDestinationCiv() == a }

    //endregion
}
