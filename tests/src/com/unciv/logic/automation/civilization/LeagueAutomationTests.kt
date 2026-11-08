package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.CityStats
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LeagueAutomationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        a = major()
        b = major()
        c = major()
        game.addCity(game.addCiv(cityStateType = "Cultured"), cityTile(), replacePalace = true)
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    //region Helpers
    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(): Civilization {
        val civ = game.addCiv()
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private val majors get() = gameInfo.civilizations.filter { it.isMajorCiv }

    private fun resolution(name: String) = game.ruleset.resolutions[name]!!

    /** The scores that depend on the strategy a civilization seeks: what the original adds then, computed from the same preference */
    private fun seeks(civ: Civilization, strategy: String) = civ.ai.grandStrategyDef()?.victoryType == strategy

    private fun evaluator(civ: Civilization) = LeagueEvaluator(civ, league)

    private fun startSession() {
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue("session should have started", league.inSession)
    }

    private fun onTable() = league.proposals.filter { !it.onHold }
    //endregion

    //region Alignment
    @Test
    fun `alignment is self for oneself and war for an enemy`() {
        assertEquals(LeagueAlignment.SELF, LeagueAutomation.evaluateAlignment(a, a))
        for (civ in listOf(a, b)) civ.getDiplomacyManager(if (civ == a) b else a)!!.diplomaticStatus = DiplomaticStatus.War
        assertEquals(LeagueAlignment.WAR, LeagueAutomation.evaluateAlignment(a, b))
    }

    @Test
    fun `friendship with a good opinion makes a friend and denunciation with a bad one a rival`() {
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(a, b))
        val toB = a.getDiplomacyManager(b)!!
        toB.setFlag(DiplomacyFlags.DeclarationOfFriendship, 20)
        assertEquals("+1 for the declaration alone is neutral", LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(a, b))
        toB.diplomaticModifiers[DiplomaticModifiers.DeclarationOfFriendship.name] = 50f // opinion weight -50: Favorable (above Neutral), +1
        assertEquals(LeagueAlignment.FRIEND, LeagueAutomation.evaluateAlignment(a, b))

        val toC = a.getDiplomacyManager(c)!!
        toC.setFlag(DiplomacyFlags.Denunciation, 20)
        toC.diplomaticModifiers[DiplomaticModifiers.Denunciation.name] = -80f // opinion weight 80 (OPINION_THRESHOLD_ENEMY): Enemy, -1
        assertEquals(LeagueAlignment.RIVAL, LeagueAutomation.evaluateAlignment(a, c))
    }

    @Test
    fun `a civilization that is not alive or no major civilization is neutral`() {
        val cityState = gameInfo.civilizations.first { it.isCityState }
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(a, cityState))
    }

    @Test
    fun `a liked proposal followed by a disliked one changes alignment until the memory expires`() {
        game.addCity(a, cityTile())
        game.addCity(a, cityTile())
        league.members.getValue(b.civID).proposalsLeft = 2
        league.proposeEnact(resolution("World's Fair"), null, b)
        assertEquals(LeagueAlignment.FRIEND, LeagueAutomation.evaluateAlignment(a, b))
        val saved = league.clone()
        assertEquals(gameInfo.turns, saved.members.getValue(a.civID).alignmentMemory.getValue(b.civID).likedProposalTurn)
        gameInfo.turns++
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        assertEquals(LeagueAlignment.RIVAL, LeagueAutomation.evaluateAlignment(a, b))
        assertEquals(-1, saved.members.getValue(a.civID).alignmentMemory.getValue(b.civID).dislikedProposalTurn)
        gameInfo.turns += 45
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(a, b))
    }

    @Test
    fun `the elected host remembers actual supporters for forty five turns`() {
        league.proposals.add(LeagueProposal(league.nextProposalId++, "Choose Host", false, null, null))
        league.inSession = true
        for (member in league.members.values) member.delegatesLeft = 2
        val election = league.proposals.single()
        league.vote(election.id, a, 2, b.civID)
        league.vote(election.id, b, 2, b.civID)
        league.vote(election.id, c, 2, c.civID)
        league.doTurn()
        assertEquals(b.civID, league.host)
        assertEquals(LeagueAlignment.FRIEND, LeagueAutomation.evaluateAlignment(b, a))
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(b, c))
        gameInfo.turns += 45
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(b, a))
    }

    @Test
    fun `a passed and a failed proposal change the proposer's opinion of voters`() {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("World's Fair"), null, b)
        startSession()
        val fair = onTable().single()
        for (civ in majors) league.vote(fair.id, civ, league.getDelegatesLeft(civ), League.YES)
        league.doTurn()
        assertEquals(LeagueAlignment.FRIEND, LeagueAutomation.evaluateAlignment(b, a))
        assertEquals(LeagueAlignment.FRIEND, LeagueAutomation.evaluateAlignment(b, c))

        league.proposals.clear()
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        gameInfo.turns++
        league.proposeEnact(resolution("Embargo"), c.civID, b)
        startSession()
        val embargo = onTable().single()
        league.vote(embargo.id, a, league.getDelegatesLeft(a), League.NO)
        league.vote(embargo.id, b, league.getDelegatesLeft(b), League.YES)
        league.vote(embargo.id, c, league.getDelegatesLeft(c), League.NO)
        league.doTurn()
        assertEquals(LeagueAlignment.RIVAL, LeagueAutomation.evaluateAlignment(b, a))
        assertEquals(LeagueAlignment.RIVAL, LeagueAutomation.evaluateAlignment(b, c))
        gameInfo.turns += 45
        assertEquals(LeagueAlignment.NEUTRAL, LeagueAutomation.evaluateAlignment(b, a))
    }
    //endregion

    //region Scoring
    @Test
    fun `an embargo on oneself is the worst thing`() {
        assertEquals(-100, evaluator(a).scoreEffects(resolution("Embargo"), a.civID))
    }

    @Test
    fun `nuclear non-proliferation is liked unless going for conquest`() {
        val expected = if (seeks(a, "Domination")) -50 else 35
        assertEquals(expected, evaluator(a).scoreEffects(resolution("Nuclear Non-Proliferation"), null))
    }

    @Test
    fun `cultural heritage sites without a wonder score -50`() {
        assertEquals(-50, evaluator(a).scoreEffects(resolution("Cultural Heritage Sites"), null))
    }

    @Test
    fun `scholars in residence are disliked by the civilization that is first in technology`() {
        a.tech.addTechnology("Pottery")
        a.tech.addTechnology("Writing")
        // first in technology: ratio -1 * 30, and a strategy of science dislikes it more
        assertEquals(-30 - (if (seeks(a, "Scientific")) 30 else 0), evaluator(a).scoreEffects(resolution("Scholars In Residence"), null))
    }

    @Test
    fun `arts funding is liked by culture and disliked by science`() {
        val expected = (if (seeks(a, "Cultural")) 80 else if (seeks(a, "Scientific")) -80 else 0)
        assertEquals(expected, evaluator(a).scoreEffects(resolution("Arts Funding"), null))
        val reverse = (if (seeks(a, "Scientific")) 80 else if (seeks(a, "Cultural")) -80 else 0)
        assertEquals(reverse, evaluator(a).scoreEffects(resolution("Sciences Funding"), null))
    }

    @Test
    fun `a luxury nobody owns scores 0`() {
        assertEquals(0, evaluator(a).scoreEffects(resolution("Ban Luxury"), "Wine"))
    }

    @Test
    fun `the vote on a resolution flips for No and for a repeal`() {
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Scholars In Residence"), null, b)
        val proposal = league.proposals.single()
        val yes = evaluator(a).scoreVote(proposal, League.YES)
        assertEquals(-yes, evaluator(a).scoreVote(proposal, League.NO))
        val repeal = LeagueProposal(99, proposal.resolution, true, b.civID, null, 5)
        assertEquals(-yes, evaluator(a).scoreVote(repeal, League.YES))
    }

    @Test
    fun `the own proposal gets the alignment bonus of the proposer`() {
        league.members.getValue(a.civID).proposalsLeft = 1
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Cultural Heritage Sites"), null, a)
        league.proposeEnact(resolution("Natural Heritage Sites"), null, b)
        val own = league.proposals.first { it.proposer == a.civID }
        val theirs = league.proposals.first { it.proposer == b.civID }
        assertEquals(evaluator(a).scoreEffects(resolution("Cultural Heritage Sites"), null) + 40, evaluator(a).scoreVote(own, League.YES))
        // below -20 and not ours: no alignment term
        assertEquals(evaluator(a).scoreEffects(resolution("Natural Heritage Sites"), null), evaluator(a).scoreVote(theirs, League.YES))
    }

    @Test
    fun `a civilization votes for itself for the World Leader and the host`() {
        for (name in listOf("World Leader", "Choose Host")) {
            val resolution = resolution(name)
            val own = evaluator(a).scorePlayerChoice(resolution, a)
            assertEquals(100, own)
            for (other in listOf(b, c)) assertTrue(own > evaluator(a).scorePlayerChoice(resolution, other))
        }
    }

    @Test
    fun `a civilization at war with the candidate refuses to vote for it`() {
        for (civ in listOf(a, b)) civ.getDiplomacyManager(if (civ == a) b else a)!!.diplomaticStatus = DiplomaticStatus.War
        assertEquals(-500, evaluator(a).scorePlayerChoice(resolution("World Leader"), b))
        assertEquals(-500, evaluator(a).scorePlayerChoice(resolution("Choose Host"), b))
    }
    //endregion

    //region Proposals
    @Test
    fun `a civilization with the right to propose proposes one legal resolution`() {
        league.members.getValue(b.civID).proposalsLeft = 1
        LeagueAutomation.automateLeague(b)
        assertEquals(0, league.members.getValue(b.civID).proposalsLeft)
        val proposal = league.proposals.single()
        assertEquals(b.civID, proposal.proposer)
        val resolution = resolution(proposal.resolution)
        assertTrue(resolution.playerProposable)
        if (resolution.proposerDecision != ResolutionDecision.None)
            assertTrue(proposal.proposerChoice in league.getChoices(resolution, b))
    }

    @Test
    fun `an AI proposes the available World's Fair when nothing else is left to propose`() {
        // Put other proposals on the table, not in effect: a repeal could otherwise outrank the Fair.
        // Cultural Heritage Sites (-50) goes on the table too: left as an alternative it would cancel the Fair (+50) to a total of 0, and then the original rolls evenly.
        game.addCity(b, cityTile())
        game.addCity(b, cityTile())
        league.members.getValue(b.civID).proposalsLeft = 1
        for (resolution in game.ruleset.resolutions.values) {
            if (resolution.name == "World's Fair") continue
            val choices = if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else league.getChoices(resolution, b)
            for (choice in choices) if (league.canProposeEnact(resolution, choice, b))
                league.proposals.add(LeagueProposal(league.nextProposalId++, resolution.name, false, b.civID, choice))
        }
        assertTrue(league.canProposeEnact(resolution("World's Fair"), null, b))
        assertTrue(evaluator(b).scoreProposal(resolution("World's Fair"), null) >= 50)
        gameInfo.turns++
        LeagueAutomation.automateLeague(b)
        assertEquals("World's Fair", league.proposals.last().resolution)
    }

    @Test
    fun `nothing happens for a civilization without proposals or delegates`() {
        for (member in league.members.values) member.proposalsLeft = 0
        LeagueAutomation.automateLeague(c)
        assertTrue(league.proposals.isEmpty())
        assertFalse(league.inSession)
    }

    @Test
    fun `the civilizations never propose the same resolution twice`() {
        for (member in league.members.values) member.proposalsLeft = 2
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        assertEquals(6, league.proposals.size)
        val keys = league.proposals.map { it.resolution to it.proposerChoice }
        assertEquals(keys.size, keys.toSet().size)
        for (civ in majors) assertFalse(league.canPropose(civ))
    }

    @Test
    fun `an active resolution can be proposed for repeal`() {
        // the only thing legal for b is the repeal if everything else is on the table or in effect
        league.members.getValue(b.civID).proposalsLeft = 1
        for (resolution in game.ruleset.resolutions.values) {
            val choices = if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else league.getChoices(resolution, b)
            for (choice in choices) if (league.canProposeEnact(resolution, choice, b))
                league.activeResolutions.add(com.unciv.logic.league.ActiveResolution(league.nextProposalId++, resolution.name, null, choice, 0))
        }
        assertTrue(league.getProposableResolutions(b).isEmpty())
        LeagueAutomation.automateLeague(b)
        val proposal = league.proposals.single()
        assertTrue(proposal.repeal)
        assertTrue(league.activeResolutions.any { it.id == proposal.targetId })
    }
    //endregion

    //region Votes
    @Test
    fun `all delegates are spent in a session`() {
        for (member in league.members.values) member.proposalsLeft = 1
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        startSession()
        val delegates = majors.associateWith { league.getDelegatesLeft(it) }
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        for (civ in majors) {
            assertEquals(0, league.getDelegatesLeft(civ))
            val cast = onTable().sumOf { proposal -> proposal.votes.filter { it.voter == civ.civID }.sumOf { it.delegates } }
            assertEquals(delegates.getValue(civ), cast)
        }
        league.doTurn()
        assertFalse(league.inSession)
        assertEquals(3, league.lastResults.size)
    }

    @Test
    fun `delegates are given up if nothing is proposed`() {
        league.proposals.clear()
        for (member in league.members.values) member.proposalsLeft = 0
        league.inSession = true
        league.members.getValue(a.civID).delegatesLeft = 2
        LeagueAutomation.automateLeague(a)
        assertEquals(0, league.getDelegatesLeft(a))
        assertEquals(2, league.members.getValue(a.civID).abstained)
    }

    @Test
    fun `an embargo on the civilization gets all of its delegates and its No`() {
        league.members.getValue(a.civID).proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        league.proposeEnact(resolution("Standing Army Tax"), null, c)
        startSession()
        val delegates = league.getDelegatesLeft(a)
        LeagueAutomation.automateLeague(a)
        val embargo = league.proposals.first { it.resolution == "Embargo" }
        val tax = league.proposals.first { it.resolution == "Standing Army Tax" }
        assertEquals(listOf(League.NO), embargo.votes.filter { it.voter == a.civID }.map { it.choice })
        assertEquals(delegates, embargo.votes.filter { it.voter == a.civID }.sumOf { it.delegates })
        assertTrue(tax.votes.none { it.voter == a.civID })
    }

    @Test
    fun `a civilization votes for itself in the World Leader vote`() {
        league.proposals.clear()
        for (member in league.members.values) member.proposalsLeft = 0
        league.proposals.add(LeagueProposal(league.nextProposalId++, "World Leader", false, null, null))
        league.inSession = true
        for (member in league.members.values) member.delegatesLeft = 3
        for (civ in majors) LeagueAutomation.automateLeague(civ)
        val votes = league.proposals.single().votes
        for (civ in majors) assertEquals(listOf(civ.civID), votes.filter { it.voter == civ.civID }.map { it.choice })
    }
    //endregion

    //region Hook
    @Test
    fun `the turn automation takes part in the World Congress`() {
        league.members.getValue(b.civID).proposalsLeft = 1
        NextTurnAutomation.automateCivMoves(b, tradeAndChangeState = false)
        assertEquals(1, league.proposals.count { it.proposer == b.civID })
    }
    //endregion

    //region End to end
    @Test
    fun `the congress runs through several sessions with automated civilizations`() {
        val sessions = HashSet<Int>()
        var votesCast = 0
        for (turn in 1..200) {
            gameInfo.turns++
            gameInfo.league = League.doGameTurn(gameInfo)
            val league = gameInfo.league!!
            for (civ in majors) LeagueAutomation.automateLeague(civ)

            // The AI leaves nothing undone
            if (league.inSession) for (civ in majors) assertEquals("turn $turn", 0, league.getDelegatesLeft(civ))
            else for (civ in majors)
                if (league.canPropose(civ))
                    assertTrue("turn $turn: $civ could still propose", league.getProposableResolutions(civ).isEmpty()
                        && league.activeResolutions.none { league.canProposeRepeal(it.id, civ) })
            if (league.lastResultsTurn >= 0 && sessions.add(league.lastResultsTurn))
                for (result in league.lastResults) votesCast += result.tally.values.sum()
        }
        val league = gameInfo.league!!
        assertTrue("sessions held: $sessions", sessions.size >= 3)
        assertTrue(votesCast > 0)
        // What passed is in effect and valid
        for (active in league.activeResolutions) {
            val resolution = game.ruleset.resolutions[active.resolution]
            assertTrue(resolution != null && resolution.playerProposable)
        }
    }
    //endregion

    //region International projects (CvLeagueAI::ScoreVoteChoiceYesNo, eLeagueProjectEnabled)
    /** Production of each civilization's cities, [fromFood] of it being what a settler makes of excess food */
    private fun setProduction(civ: Civilization, production: Float, fromFood: Float = 0f) {
        for (city in civ.cities) {
            city.cityStats.currentCityStats = Stats(production = production)
            city.cityStats.finalStatList.clear()
            if (fromFood > 0f) city.cityStats.finalStatList[CityStats.EXCESS_FOOD_TO_PRODUCTION] = Stats(production = fromFood)
        }
    }

    @Test
    fun `project support follows the production rank and the culture or science grand strategy`() {
        setProduction(a, 30f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        a.ai.grandStrategy = GrandStrategyAi.CULTURE
        // first of three: ratio 1, +40 and strong production
        assertEquals(40 + 30, evaluator(a).scoreEffects(resolution("World's Fair"), null))
        assertEquals(40 + 40, evaluator(a).scoreEffects(resolution("International Games"), null))
        assertEquals("not seeking science", 40 + 10, evaluator(a).scoreEffects(resolution("International Space Station"), null))
        a.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        assertEquals(40 + 50, evaluator(a).scoreEffects(resolution("International Space Station"), null))
        assertEquals(40 + 10, evaluator(a).scoreEffects(resolution("World's Fair"), null))

        // second of three: ratio 2/3, +20 and still strong
        b.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(20 + 30, evaluator(b).scoreEffects(resolution("World's Fair"), null))
        // last of three: ratio 1/3, -20 and weak, so the strategy bonus is not given
        c.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(-20 + 10, evaluator(c).scoreEffects(resolution("World's Fair"), null))
    }

    @Test
    fun `production that comes from excess food does not count as production might`() {
        // 30 production of which 25 from food is 5 of might: last of three
        setProduction(a, 30f, fromFood = 25f)
        setProduction(b, 20f)
        setProduction(c, 10f)
        a.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(-20 + 10, evaluator(a).scoreEffects(resolution("World's Fair"), null))
    }
    //endregion

    //region Vote commitments (CvLeagueAI vote trading)
    @Test
    fun `desire levels follow the bands of EvaluateDesire`() {
        val expected = listOf(-106 to LeagueDesire.NEVER, -105 to LeagueDesire.STRONG_DISLIKE, -76 to LeagueDesire.STRONG_DISLIKE,
            -75 to LeagueDesire.DISLIKE, -46 to LeagueDesire.DISLIKE, -45 to LeagueDesire.WEAK_DISLIKE, -16 to LeagueDesire.WEAK_DISLIKE,
            -15 to LeagueDesire.NEUTRAL, 15 to LeagueDesire.NEUTRAL, 16 to LeagueDesire.WEAK_LIKE, 45 to LeagueDesire.WEAK_LIKE,
            46 to LeagueDesire.LIKE, 75 to LeagueDesire.LIKE, 76 to LeagueDesire.STRONG_LIKE, 105 to LeagueDesire.STRONG_LIKE, 106 to LeagueDesire.ALWAYS)
        for ((score, desire) in expected) assertEquals("score $score", desire, LeagueDesire.of(score))
    }

    private fun proposeEmbargoOnA(): LeagueProposal {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(b.civID).proposalsLeft = 1
        league.proposeEnact(resolution("Embargo"), a.civID, b)
        return onTable().single()
    }

    @Test
    fun `an AI wants the votes of another civilization on the choice it desires most`() {
        val embargo = proposeEmbargoOnA()
        // An embargo on itself scores -100 for yes (strong dislike) and +100 for no (strong like)
        assertEquals(LeagueDesire.STRONG_DISLIKE, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.YES, 1, repeal = false))
        assertEquals(LeagueDesire.STRONG_LIKE, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.NO, 1, repeal = false))
        assertEquals("not on the table as a repeal", LeagueDesire.NEVER, LeagueAutomation.evaluateVoteForTrade(a, embargo.id, League.NO, 1, repeal = true))

        val desired = LeagueAutomation.desiredVoteCommitments(a, c).single()
        assertEquals(a.civID, desired.toCiv)
        assertEquals(embargo.id, desired.proposalId)
        assertEquals(League.NO, desired.choice)
        assertTrue(desired.enact)
        assertTrue(league.getCoreVotes(c) > 0)
        assertEquals("the core votes of the civilization asked", league.getCoreVotes(c), desired.votes)
    }

    @Test
    fun `committed votes are cast when the session starts and the commitment is used up`() {
        val embargo = proposeEmbargoOnA()
        // A proposal that is not on the table: dropped (first, as a member with no votes left keeps what it could not honor, as in the DLL)
        LeagueAutomation.addVoteCommitment(c, b, 999, League.YES, 1, repeal = false)
        LeagueAutomation.addVoteCommitment(c, b, embargo.id, League.YES, 1, repeal = false)
        startSession()
        assertEquals(1, embargo.votes.filter { it.voter == c.civID && it.choice == League.YES }.sumOf { it.delegates })
        assertTrue(league.members.getValue(c.civID).voteCommitments.isEmpty())
    }

    @Test
    fun `war cancels the commitments to the enemy`() {
        val embargo = proposeEmbargoOnA()
        LeagueAutomation.addVoteCommitment(c, b, embargo.id, League.YES, 1, repeal = false)
        LeagueAutomation.addVoteCommitment(c, a, embargo.id, League.NO, 1, repeal = false)
        c.getDiplomacyManager(b)!!.declareWar()
        assertEquals(listOf(a.civID), league.members.getValue(c.civID).voteCommitments.map { it.toCiv })
    }
    //endregion
}
