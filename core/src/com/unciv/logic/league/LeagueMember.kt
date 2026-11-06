package com.unciv.logic.league

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.Counter

/**
 * The state of one member of the World Congress that can vote and propose, see [League.members] (`CvLeague::Member`, CvVotingClasses.h:413-428).
 * City-states are members in the original too, but only as sources of delegates and as targets, so they have no row.
 */
class LeagueMember : IsPartOfGameInfoSerialization {
    /** Permanent delegates that failed World Leader votes gave (CvVotingClasses.cpp:5446-5456) */
    var extraDelegates = 0
    /** Delegates not yet spent in the running session (`iVotes`) */
    var delegatesLeft = 0
    /** Delegates at the start of the running session, for display */
    var delegatesAtStart = 0
    /** Delegates given up in the running session (`iAbstainedVotes`) */
    var abstained = 0
    /** Proposals the member may still make before the next session (`bMayPropose` && `iProposals`) */
    var proposalsLeft = 0

    fun clone(): LeagueMember {
        val toReturn = LeagueMember()
        toReturn.extraDelegates = extraDelegates
        toReturn.delegatesLeft = delegatesLeft
        toReturn.delegatesAtStart = delegatesAtStart
        toReturn.abstained = abstained
        toReturn.proposalsLeft = proposalsLeft
        return toReturn
    }
}

/** Delegates that [voter] put on [choice]: "Yes"/"No" or the id of a civilization (`CvResolutionDecision::PlayerVote`) */
class LeagueVote() : IsPartOfGameInfoSerialization {
    var voter = ""
    var delegates = 0
    var choice = ""

    constructor(voter: String, delegates: Int, choice: String) : this() {
        this.voter = voter
        this.delegates = delegates
        this.choice = choice
    }

    fun clone() = LeagueVote(voter, delegates, choice)
}

/**
 * A resolution on the table of a session, to be enacted or (if [repeal]) to be repealed (`CvEnactProposal`/`CvRepealProposal`).
 * @property proposer civ id, `null` for the proposals the World Congress makes itself (host election, World Leader), which cost nobody a proposal
 * @property proposerChoice civ id, luxury, religion or ideology name that the proposer chose, `null` if the resolution has no choice
 * @property targetId for a repeal: [ActiveResolution.id] of the resolution to repeal, else 0
 * @property onHold parked during a special session, back on the table after it (`*OnHold` lists)
 */
class LeagueProposal() : IsPartOfGameInfoSerialization {
    var id = 0
    var resolution = ""
    var repeal = false
    var proposer: String? = null
    var proposerChoice: String? = null
    var targetId = 0
    var onHold = false
    var votes = ArrayList<LeagueVote>()

    constructor(
        id: Int, resolution: String, repeal: Boolean, proposer: String?, proposerChoice: String?,
        targetId: Int = 0, onHold: Boolean = false
    ) : this() {
        this.id = id
        this.resolution = resolution
        this.repeal = repeal
        this.proposer = proposer
        this.proposerChoice = proposerChoice
        this.targetId = targetId
        this.onHold = onHold
    }

    fun clone(): LeagueProposal {
        val toReturn = LeagueProposal(id, resolution, repeal, proposer, proposerChoice, targetId, onHold)
        toReturn.votes.addAll(votes.map { it.clone() })
        return toReturn
    }
}

/** An enacted resolution with ongoing effects (`CvActiveResolution`). [choice] is what the proposer chose, [turnEnacted] a game turn. */
class ActiveResolution() : IsPartOfGameInfoSerialization {
    var id = 0
    var resolution = ""
    var proposer: String? = null
    var choice: String? = null
    var turnEnacted = 0

    constructor(id: Int, resolution: String, proposer: String?, choice: String?, turnEnacted: Int) : this() {
        this.id = id
        this.resolution = resolution
        this.proposer = proposer
        this.choice = choice
        this.turnEnacted = turnEnacted
    }

    fun clone() = ActiveResolution(id, resolution, proposer, choice, turnEnacted)
}

/**
 * The outcome of one proposal of the last finished session, see [League.lastResults].
 * @property choice what the resolution is about: the civilization that won a vote for a civilization (host, World Leader), else the choice of the proposer
 * @property tally delegates per choice ("Yes"/"No" or civ id)
 */
class LeagueResultEntry() : IsPartOfGameInfoSerialization {
    var resolution = ""
    var choice: String? = null
    var passed = false
    var repeal = false
    var tally = Counter<String>()

    constructor(resolution: String, choice: String?, passed: Boolean, repeal: Boolean, tally: Counter<String>) : this() {
        this.resolution = resolution
        this.choice = choice
        this.passed = passed
        this.repeal = repeal
        this.tally = tally
    }

    fun clone() = LeagueResultEntry(resolution, choice, passed, repeal, tally.clone())
}
