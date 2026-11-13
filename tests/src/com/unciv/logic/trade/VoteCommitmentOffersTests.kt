package com.unciv.logic.trade

import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.civilization.VoteCommitmentItem
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  What a player can promise in a deal and what the deal then checks (CvDeal::IsPossibleToTradeItem, CvDeal::AddVoteCommitment and the
 *  finalizing of TRADE_ITEM_VOTE_COMMITMENT): the human [a] asks the AI [b] for its votes on c's embargo against a.
 */
@RunWith(GdxTestRunner::class)
class VoteCommitmentOffersTests {
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
        a = major(isPlayer = true)
        b = major()
        c = major()
        game.addCity(game.addCiv(cityStateType = "Cultured"), cityTile(), replacePalace = true)
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer)
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun proposeEmbargoOnA(): LeagueProposal {
        for (member in league.members.values) member.proposalsLeft = 0
        league.members.getValue(c.civID).proposalsLeft = 1
        league.proposeEnact(game.ruleset.resolutions["Embargo"]!!, a.civID, c)
        return league.proposals.single()
    }

    /** a's diplomat schmoozes in b's capital, so b can promise its votes to a */
    private fun sendDiplomat() {
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        val spy = a.espionageManager.addSpy()
        spy.moveTo(b.getCapital()!!, asDiplomat = true)
        repeat(20) { if (a.espionageManager.getActiveDiplomatFor(b) == null) a.espionageManager.endTurn() }
        assertTrue(a.espionageManager.getActiveDiplomatFor(b) != null)
    }

    private fun offer(proposal: LeagueProposal, choice: String, votes: Int = league.getCoreVotes(b), repeal: Boolean = false) =
        TradeOffer(VoteCommitmentItem.name(proposal.id, choice, repeal), TradeOfferType.VoteCommitment, votes, gameInfo.speed)

    private fun votesFromB() = TradeLogic(a, b).theirAvailableOffers.filter { it.type == TradeOfferType.VoteCommitment }

    private fun valid(vararg theirOffers: TradeOffer): Boolean {
        val trade = Trade()
        trade.theirOffers.addAll(theirOffers)
        return TradeEvaluation().isTradeValid(trade, a, b)
    }

    @Test
    fun `a civilization with a visiting diplomat offers all its votes on each choice of each proposal`() {
        val proposal = proposeEmbargoOnA()
        sendDiplomat()
        val offers = votesFromB()
        assertEquals(setOf(League.YES, League.NO), offers.map { VoteCommitmentItem.parse(it)!!.choice }.toSet())
        assertEquals(2, offers.size)
        for (offered in offers) {
            assertEquals(league.getCoreVotes(b), offered.amount)
            assertTrue(league.getCoreVotes(b) > 0)
            assertEquals(proposal.id, VoteCommitmentItem.parse(offered)!!.proposalId)
            assertFalse(VoteCommitmentItem.parse(offered)!!.repeal)
        }
        // b has no diplomat in a's capital, so a cannot promise b its votes
        assertTrue(TradeLogic(a, b).ourAvailableOffers.none { it.type == TradeOfferType.VoteCommitment })
    }

    @Test
    fun `no votes are offered without a diplomat, in a session, on a proposal on hold or at war`() {
        val proposal = proposeEmbargoOnA()
        assertTrue("no diplomat", votesFromB().isEmpty())
        sendDiplomat()
        assertEquals(2, votesFromB().size)
        proposal.onHold = true
        assertTrue("on hold", votesFromB().isEmpty())
        proposal.onHold = false
        league.inSession = true
        assertTrue("in session", votesFromB().isEmpty())
        league.inSession = false
        assertEquals(2, votesFromB().size)
        a.getDiplomacyManager(b)!!.declareWar()
        assertTrue("at war", votesFromB().isEmpty())
    }

    @Test
    fun `a deal holds one vote commitment per civilization, of a proposal on the table with a valid choice and its votes`() {
        val proposal = proposeEmbargoOnA()
        sendDiplomat()
        assertTrue(valid(offer(proposal, League.NO)))
        assertTrue("fewer votes than the core votes", valid(offer(proposal, League.NO, votes = 1)))
        assertFalse("two commitments of one civilization", valid(offer(proposal, League.NO), offer(proposal, League.YES)))
        assertFalse("a proposal that is not there", valid(offer(LeagueProposal(999, "Embargo", false, null, null), League.NO)))
        assertFalse("an enactment is not a repeal", valid(offer(proposal, League.NO, repeal = true)))
        assertFalse("a choice nobody can make", valid(offer(proposal, "Nobody")))
        assertFalse("no votes", valid(offer(proposal, League.NO, votes = 0)))
        assertFalse("more votes than the core votes", valid(offer(proposal, League.NO, votes = league.getCoreVotes(b) + 1)))
    }

    @Test
    fun `an accepted deal makes b promise its votes and a stale one promises nothing`() {
        val proposal = proposeEmbargoOnA()
        sendDiplomat()
        val stale = TradeLogic(a, b)
        stale.currentTrade.theirOffers.add(offer(LeagueProposal(999, "Embargo", false, null, null), League.NO))
        stale.acceptTrade()
        assertTrue(league.members.getValue(b.civID).voteCommitments.isEmpty())

        val logic = TradeLogic(a, b)
        logic.currentTrade.theirOffers.add(offer(proposal, League.NO))
        logic.acceptTrade()
        val promised = league.members.getValue(b.civID).voteCommitments.single()
        assertEquals(a.civID, promised.toCiv)
        assertEquals(proposal.id, promised.proposalId)
        assertEquals(League.NO, promised.choice)
        assertEquals(league.getCoreVotes(b), promised.votes)
        assertTrue(promised.enact)
        // and b cannot promise more now
        assertFalse(LeagueAutomation.canCommitVote(b, a))
    }

    @Test
    fun `the offer says which resolution is voted on`() {
        val proposal = proposeEmbargoOnA()
        val item = VoteCommitmentItem.parse(offer(proposal, League.NO))!!
        assertEquals("Vote [${League.NO}] on [Embargo]", VoteCommitmentItem.text(item, league))
        val repeal = VoteCommitmentItem(proposal.id, League.YES, true)
        assertEquals("a repeal that is not proposed is named by number", "Vote [${League.YES}] on proposal [${proposal.id}]", VoteCommitmentItem.text(repeal, league))
    }
}
