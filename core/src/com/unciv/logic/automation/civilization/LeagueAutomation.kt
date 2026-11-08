package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * World Congress proposals and votes (CvLeagueAI, CvVotingClasses.cpp:7365-9860).
 * No vote trading, delegate deals, diplomats, team alliances or resurrection.
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
        if (league.inSession && league.canVote(civ)) doVotes(civ, league)
    }

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

/**
 * Scores what [civ] likes in the World Congress (CvLeagueAI::ScoreVoteChoice and friends, CvVotingClasses.cpp:7905-8030, 8759-9600).
 * Positive is like. The scores are the integers of the original.
 *
 * ## Mapping layer: inputs of the original and what stands in for them
 * - Grand strategy (`bSeeking*`): CvDiplomacyAI::IsGoingFor* of the active grand strategy ([com.unciv.logic.automation.strategy.CivAi]):
 *   Conquest, United Nations (diplomacy), Spaceship (space race), Culture. Before the first grand strategy is chosen none of them is sought.
 * - Production might: the sum of the production of the cities (CvPlayer::calculateProductionMight). Tech rank: the number of researched technologies
 *   (CvTechAI::GetTechRatio, position among the living major civilizations, ties by civilization order).
 * - Opinion: [DiplomacyManager.relationshipIgnoreAfraid]; "opinion above neutral" is Favorable or better, "at most enemy" Enemy or Unforgivable,
 *   "below neutral" Competitor or worse. Approach "hostile or worse" is a denunciation or an Enemy level, approach "afraid or friendly" has no equivalent.
 * - Warmonger threat at least severe: the [DiplomaticModifiers.WarMongerer] modifier of the target at most [SEVERE_WARMONGER]. Humans keep none.
 * - Trade connections: routes between the two civilizations in either direction. Morocco's trait (incoming route yield): [UniqueType.TradeRouteGoldForOrigin],
 *   Venice's (more routes): a positive [UniqueType.TradeRouteSlotsPercent]. Possible routes: [com.unciv.logic.civilization.managers.TradeRouteManager.getNumSlots].
 * - Gross gold and unit maintenance: the gold entries of the stat map of the next turn, "Unit upkeep" being the maintenance.
 * - Wonders: the world wonders in the cities. Natural wonders and resources "in owned plots": the tiles of the cities. Empire unhappy: happiness below 0.
 * - Great person improvements: tiles with a great improvement; landmarks: tiles with the improvement [LANDMARK].
 * - Sciencey unique unit (Merchant of Venice): the civilization's equivalent of [GREAT_MERCHANT] is another unit.
 * - Resolutions are found by effect (uniques and typed fields), except Nuclear Non-Proliferation and the projects, which have no effect field: by name.
 *
 * ## Gaps
 * Teams (no LEADER or teammate ALLY), resurrection (LIBERATOR), diplomat knowledge and vote commitments (`9408`),
 * the approach of the diplomacy AI, resources of civilizations met only through trade.
 * Congress proposal/host memories are saved with members and expire after 45 game turns; Unciv's general
 * diplomacy opinion does not currently include the DLL's additional Congress opinion weights.
 *
 * Deviations: the choice for a tie between equal best choices is No for yes/no (the status quo) and the civilization itself for civilization votes,
 * the original leaves it to an unstable sort. A division by zero when the gross gold is 0 (CvVotingClasses.cpp:9147) counts as ratio 0.
 */
class LeagueEvaluator(private val civ: Civilization, private val league: League) {
    companion object {
        /** The [DiplomaticModifiers.WarMongerer] value from which the warmonger threat counts as THREAT_SEVERE */
        const val SEVERE_WARMONGER = -20f
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

    //region Alignment

    /** CvLeagueAI::EvaluateAlignment (CvVotingClasses.cpp:7905-8030) */
    fun evaluateAlignment(other: Civilization): LeagueAlignment {
        if (!other.isAlive() || !other.isMajorCiv) return LeagueAlignment.NEUTRAL
        if (other == civ) return LeagueAlignment.SELF
        if (civ.isAtWarWith(other)) return LeagueAlignment.WAR

        var sum = 0
        val ours = civ.getIdeology()
        val theirs = other.getIdeology()
        val memory = league.members[civ.civID]?.alignmentMemory?.get(other.civID)
        if (memory != null) {
            val turn = gameInfo.turns
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

    //endregion
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
        val alignment = evaluateAlignment(candidate)
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
            if (proposer != null) score += when (evaluateAlignment(proposer)) {
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

        val project = projects[resolution.name]
        if (project != null) score += scoreProject(project)

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

    /** Production rank decides, then the project: (CvVotingClasses.cpp:8806-8898) */
    private fun scoreProject(project: Pair<Int, String>): Int {
        val alive = gameInfo.getAliveMajorCivs()
        if (alive.isEmpty()) return 0
        val ours = productionMight(civ)
        val higher = alive.count { it != civ && productionMight(it) > ours }
        val ratio = ((alive.size - higher).toFloat() / alive.size).coerceIn(0f, 1f)
        var score = 0
        var strong = false
        when {
            ratio >= 0.75f -> { score += 40; strong = true }
            ratio >= 0.5f -> { score += 20; strong = true }
            ratio >= 0.25f -> score -= 20
            else -> score -= 40
        }
        val seeking = if (project.second == CULTURE) seekingCulture else seekingScience
        score += if (seeking && strong) project.first else 10
        return score
    }

    private fun productionMight(c: Civilization) = c.cities.sumOf { it.cityStats.currentCityStats.production.toInt() }

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
            (diplomacy?.getModifier(DiplomaticModifiers.WarMongerer) ?: 0f) <= SEVERE_WARMONGER -> 40
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
        val ratio = (getTechRatio() - 0.5f) * 2f // -1 if in first, 1 if in last
        return if (ratio < 0f) (ratio * 30).toInt() + (if (seekingScience) -30 else 0)
        else (ratio * 50).toInt() + (if (seekingScience) 40 else 0)
    }

    /** CvTechAI::GetTechRatio (CvTechAI.cpp:248-292): 0 for the civilization with most technologies, 1 for the one with fewest */
    private fun getTechRatio(): Float {
        val ranked = gameInfo.getAliveMajorCivs().sortedByDescending { it.tech.researchedTechnologies.size }
        if (ranked.size < 2) return 0f
        return ranked.indexOf(civ).coerceAtLeast(0) / (ranked.size - 1).toFloat()
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
