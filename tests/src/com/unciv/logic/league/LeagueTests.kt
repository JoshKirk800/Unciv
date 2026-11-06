package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LeagueTests {
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
        meetAll()
    }

    //region Helpers
    private fun cityTile() = game.tileMap.tileList[7 * nextCell++]

    private fun major(vararg uniques: String, isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(*uniques, isPlayer = isPlayer)
        game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun cityState(withCity: Boolean = true): Civilization {
        val civ = game.addCiv(cityStateType = "Cultured")
        if (withCity) game.addCity(civ, cityTile(), replacePalace = true)
        return civ
    }

    private fun meetAll() {
        val majors = gameInfo.civilizations.filter { it.isMajorCiv }
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
    }

    private fun giveEra(civ: Civilization, era: String) {
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == era }.name)
    }

    private fun printingPress(vararg civs: Civilization) {
        for (civ in civs) civ.tech.addTechnology("Printing Press")
    }

    private fun found(): League {
        printingPress(a)
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        return league
    }

    private fun resolution(name: String) = game.ruleset.resolutions[name]!!

    private fun propose(civ: Civilization, name: String, choice: String? = null) =
        league.proposeEnact(resolution(name), choice, civ)

    /** Each proposer proposes the first thing that is legal */
    private fun proposeEverything() {
        for (civ in gameInfo.civilizations.filter { it.isMajorCiv }) {
            while (league.canPropose(civ)) {
                val resolution = league.getProposableResolutions(civ).first()
                val choice = if (resolution.proposerDecision == ResolutionDecision.None) null else league.getChoices(resolution, civ).first()
                league.proposeEnact(resolution, choice, civ)
            }
        }
    }

    private fun startSession() {
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue("session should have started", league.inSession)
    }

    private fun abstainAll() {
        for (civ in gameInfo.civilizations.filter { it.isMajorCiv }) league.abstainRemaining(civ)
    }

    /** Proposals, a session in which all abstain, its end */
    private fun playSessionOfAbstainers() {
        proposeEverything()
        startSession()
        abstainAll()
        league.doTurn()
        assertFalse(league.inSession)
    }

    private fun voteAll(proposal: LeagueProposal, civ: Civilization, choice: String) =
        league.vote(proposal.id, civ, league.getDelegatesLeft(civ), choice)

    private fun resolutionProposal(name: String) = league.proposals.first { it.resolution == name }
    //endregion

    //region Founding
    @Test
    fun `nobody founds the league without Printing Press`() {
        assertNull(League.tryFound(gameInfo))
    }

    @Test
    fun `the league needs two living major civilizations`() {
        val solo = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        solo.makeHexagonalMap(5)
        val only = solo.addCiv()
        solo.addCity(only, solo.tileMap.tileList[0], replacePalace = true)
        val cityState = solo.addCiv(cityStateType = "Cultured")
        solo.addCity(cityState, solo.tileMap.tileList[20], replacePalace = true)
        only.tech.addTechnology("Printing Press")
        assertNull(League.tryFound(solo.gameInfo))
    }

    @Test
    fun `the founder must have met every other major civilization`() {
        val strangers = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        strangers.makeHexagonalMap(8)
        val x = strangers.addCiv(); val y = strangers.addCiv(); val z = strangers.addCiv()
        for ((index, civ) in listOf(x, y, z).withIndex()) strangers.addCity(civ, strangers.tileMap.tileList[10 * index], replacePalace = true)
        x.diplomacyFunctions.makeCivilizationsMeet(y)
        y.diplomacyFunctions.makeCivilizationsMeet(z)
        x.tech.addTechnology("Printing Press")
        assertNull("x has not met z", League.tryFound(strangers.gameInfo))

        y.tech.addTechnology("Printing Press")
        val league = League.tryFound(strangers.gameInfo)!!
        assertEquals("y met everybody", y.civID, league.host)
    }

    @Test
    fun `the first civilization in order that qualifies founds the league and is the host`() {
        printingPress(c, b)
        val league = League.tryFound(gameInfo)!!
        assertEquals(b.civID, league.host)
        assertEquals(setOf(a.civID, b.civID, c.civID), league.members.keys)
    }

    @Test
    fun `city-states are no members that propose or vote but count as members`() {
        val cs = cityState()
        found()
        assertFalse(league.members.containsKey(cs.civID))
        assertTrue(league.isMember(cs))
        assertEquals(0, league.getDelegates(cs))
        assertFalse(league.isMember(game.addBarbarianCiv()))
    }

    @Test
    fun `a founded league holds no session and counts down by the era`() {
        found()
        assertFalse(league.inSession)
        assertEquals("Renaissance era", league.lastSpecialSession)
        assertEquals("Renaissance era", league.lastEraTrigger)
        assertEquals(30, league.turnsUntilSession)
        assertFalse(league.unitedNations)
    }

    @Test
    fun `a league founded in a later era is governed by that era`() {
        for (civ in listOf(a, b, c)) giveEra(civ, "Modern era")
        found()
        assertEquals("Modern era", league.lastSpecialSession)
        assertEquals(20, league.turnsUntilSession)
    }

    @Test
    fun `eras after the last session are clamped and make the league the United Nations`() {
        for (civ in listOf(a, b, c)) giveEra(civ, "Future era")
        found()
        assertEquals("Atomic era", league.lastSpecialSession)
        assertTrue(league.unitedNations)
        assertEquals(10, league.turnsUntilSession)
    }

    @Test
    fun `without resolutions in the ruleset there is no league`() {
        printingPress(a)
        val resolutions = game.ruleset.resolutions.toMap()
        game.ruleset.resolutions.clear()
        assertNull(League.doGameTurn(gameInfo))
        assertNull(gameInfo.league)

        game.ruleset.resolutions.putAll(resolutions)
        val founded = League.doGameTurn(gameInfo)
        assertNotNull(founded)
        gameInfo.league = founded
        assertTrue("the next call ticks the same league", League.doGameTurn(gameInfo) === founded)
    }
    //endregion

    //region Delegates
    @Test
    fun `delegates are those of the era plus one set for the host`() {
        found()
        assertEquals(2, league.getDelegates(a)) // 1 + 1 for the host
        assertEquals(1, league.getDelegates(b))
        league.lastSpecialSession = "Modern era"
        assertEquals(5, league.getDelegates(a)) // 3 + 2
        assertEquals(3, league.getDelegates(b))
    }

    @Test
    fun `failed World Leader votes give permanent delegates`() {
        found()
        league.members.getValue(b.civID).extraDelegates = 2
        assertEquals(3, league.getDelegates(b))
        assertEquals(1, league.getDelegates(c))
    }

    @Test
    fun `allied city-states give delegates by the era`() {
        found()
        val first = cityState()
        val second = cityState()
        val dead = cityState(withCity = false)
        for (cs in listOf(first, second, dead)) cs.allyCiv = b
        assertEquals("the Renaissance gives nothing for city-states", 1, league.getDelegates(b))
        league.lastSpecialSession = "Industrial era"
        assertEquals("2 for the civilization, one for each living ally", 4, league.getDelegates(b))
        league.lastSpecialSession = "Atomic era"
        assertEquals(4 + 2 * 2, league.getDelegates(b))
    }

    @Test
    fun `World Congress Delegates uniques add up`() {
        val rich = major("[2] World Congress Delegates")
        meetAll()
        found()
        assertEquals(1 + 2, league.getDelegates(rich))
    }

    @Test
    fun `a World Religion gives delegates to the civilizations that follow it in most cities`() {
        found()
        val religion = game.addReligion(a)
        religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        val converted = a.cities.first()
        converted.religion.addPressure(religion.name, 1000)
        val bCity = b.cities.first()
        league.activeResolutions.add(ActiveResolution(1, "World Religion", null, religion.name, 0))
        league.setTransients(gameInfo)

        assertEquals("a: 1 + 1 host + 2 for the religion", 4, league.getDelegates(a))
        assertEquals(1, league.getDelegates(b))
        bCity.religion.addPressure(religion.name, 1000)
        assertEquals(3, league.getDelegates(b))
    }

    @Test
    fun `a World Ideology gives delegates to the civilizations that follow it`() {
        found()
        giveEra(b, "Modern era")
        b.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        giveEra(c, "Modern era")
        c.policies.adopt(game.ruleset.policyBranches["Order"]!!)
        league.activeResolutions.add(ActiveResolution(1, "World Ideology", null, "Freedom", 0))
        league.setTransients(gameInfo)

        assertEquals(3, league.getDelegates(b))
        assertEquals(1, league.getDelegates(c))
        assertEquals(2, league.getDelegates(a))
    }
    //endregion

    //region Session cadence
    @Test
    fun `the interval between sessions is scaled by the game speed with integer arithmetic`() {
        val expected = mapOf("Quick" to (20 to 16), "Standard" to (30 to 25), "Epic" to (45 to 37), "Marathon" to (90 to 75))
        for ((speed, turns) in expected) {
            game.setSpeed(speed)
            val founded = League().also { it.setTransients(gameInfo) }
            founded.lastSpecialSession = "Renaissance era"
            assertEquals("Renaissance $speed", turns.first, founded.getSessionTurnInterval())
            founded.lastSpecialSession = "Industrial era"
            assertEquals("Industrial $speed", turns.second, founded.getSessionTurnInterval())
        }
    }

    @Test
    fun `the countdown stops at zero while somebody may still propose`() {
        found()
        league.turnsUntilSession = 2
        league.doTurn()
        assertEquals(1, league.turnsUntilSession)
        league.doTurn()
        assertEquals(0, league.turnsUntilSession)
        assertFalse("the host and the largest delegation have not proposed", league.inSession)
        league.doTurn()
        assertEquals(0, league.turnsUntilSession)
        assertFalse(league.inSession)
    }

    @Test
    fun `the session starts once nobody may propose and ends when all delegates are spent`() {
        found()
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        assertFalse(league.canPropose(a))
        startSession()
        assertEquals(listOf(2, 1, 1), listOf(a, b, c).map { league.getDelegatesLeft(it) })
        assertTrue(league.canVote(a))

        league.doTurn()
        assertTrue("delegates are left", league.inSession)

        abstainAll()
        league.doTurn()
        assertFalse(league.inSession)
        assertEquals(30, league.turnsUntilSession)
        assertTrue("two proposers again", league.canPropose(a) && league.canPropose(b))
    }

    @Test
    fun `the warning is given when the countdown reaches the warning turns`() {
        found()
        gameInfo.turns = 40
        league.turnsUntilSession = 6
        league.doTurn()
        assertEquals(40, league.sessionSoonTurn)
        a.notifications.clear()
        league.notifyStartTurn(a)
        assertTrue(a.notifications.any { it.text == "World Congress session in [5] turns" })
        assertTrue(a.notifications.any { it.text == "Your proposal is wanted by the World Congress" })
    }

    @Test
    fun `a special session elects the host and puts the proposals on hold`() {
        found()
        for (civ in listOf(a, b, c)) giveEra(civ, "Industrial era")
        league.turnsUntilSession = 10
        league.doTurn()
        assertFalse("the proposals have still to be made", league.inSession)
        assertEquals(9, league.turnsUntilSession)

        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        league.doTurn()

        assertTrue(league.inSession)
        assertEquals("Industrial era", league.currentSpecialSession)
        assertEquals("Industrial era", league.lastEraTrigger)
        assertEquals("a special session does not count down", 9, league.turnsUntilSession)
        assertEquals(2, league.proposals.count { it.onHold })
        val election = resolutionProposal("Choose Host")
        assertFalse(election.onHold)
        assertNull("the World Congress proposes it", election.proposer)
        assertEquals(listOf(3, 2, 2), listOf(a, b, c).map { league.getDelegatesLeft(it) })

        voteAll(election, a, c.civID)
        voteAll(election, b, b.civID)
        voteAll(election, c, c.civID)
        league.doTurn()

        assertFalse(league.inSession)
        assertEquals(c.civID, league.host)
        assertEquals("Industrial era", league.lastSpecialSession)
        assertNull(league.currentSpecialSession)
        assertEquals(listOf("Standing Army Tax", "Cultural Heritage Sites"), league.proposals.map { it.resolution })
        assertTrue("back on the table", league.proposals.none { it.onHold })
        assertEquals(9, league.turnsUntilSession)
        assertEquals(3, league.getDelegates(c))
        assertEquals(2, league.getDelegates(a))
        assertEquals(25, league.getSessionTurnInterval())
        val result = league.lastResults.single()
        assertTrue(result.passed)
        assertEquals(c.civID, result.choice)
    }

    @Test
    fun `a special session waits until nobody may propose or vote`() {
        found()
        league.members.getValue(b.civID).delegatesLeft = 1
        league.members.values.forEach { it.proposalsLeft = 0 }
        for (civ in listOf(a, b, c)) giveEra(civ, "Industrial era")
        league.doTurn()
        assertFalse(league.inSession)
        league.members.getValue(b.civID).delegatesLeft = 0
        league.doTurn()
        assertEquals("Industrial era", league.currentSpecialSession)
    }

    @Test
    fun `only the last era that was reached holds a special session`() {
        found()
        league.members.values.forEach { it.proposalsLeft = 0 }
        for (civ in listOf(a, b, c)) giveEra(civ, "Modern era")
        league.doTurn()
        assertEquals("Modern era", league.currentSpecialSession)
        assertEquals("Modern era", league.lastEraTrigger)
    }

    @Test
    fun `the Atomic era special session makes the league the United Nations`() {
        found()
        league.members.values.forEach { it.proposalsLeft = 0 }
        for (civ in listOf(a, b, c)) giveEra(civ, "Atomic era")
        league.doTurn()
        assertEquals("Atomic era", league.currentSpecialSession)
        assertTrue(league.unitedNations)
    }
    //endregion

    //region Proposals
    @Test
    fun `the host and the largest other delegation may propose once`() {
        found()
        assertTrue(league.canPropose(a))
        assertTrue(league.canPropose(b))
        assertFalse(league.canPropose(c))
        propose(a, "Standing Army Tax")
        assertFalse("one proposal each", league.canPropose(a))
        assertTrue(league.canPropose(b))
        assertThrows(IllegalArgumentException::class.java) { propose(a, "Cultural Heritage Sites") }
    }

    @Test
    fun `privileges go to the host and the largest delegation after a session`() {
        found()
        league.members.getValue(c.civID).extraDelegates = 3
        playSessionOfAbstainers()
        assertTrue(league.canPropose(a))
        assertTrue(league.canPropose(c))
        assertFalse(league.canPropose(b))
    }

    @Test
    fun `players cannot propose what the World Congress proposes itself`() {
        found()
        assertFalse(league.canProposeEnact(resolution("Choose Host"), null, a))
        assertFalse(league.canProposeEnact(resolution("World Leader"), null, a))
        assertTrue(league.canProposeEnact(resolution("Standing Army Tax"), null, a))
        assertFalse("c has no proposal", league.canProposeEnact(resolution("Standing Army Tax"), null, c))
        assertFalse("there is no choice", league.canProposeEnact(resolution("Standing Army Tax"), "x", a))
        assertEquals(setOf("Standing Army Tax", "Scholars In Residence", "Cultural Heritage Sites", "Natural Heritage Sites",
            "Arts Funding", "Sciences Funding", "Embargo", "World's Fair"), league.getProposableResolutions(a).map { it.name }.toSet())
    }

    @Test
    fun `a resolution that is on the table or in effect cannot be proposed again`() {
        found()
        propose(a, "Standing Army Tax")
        assertFalse(league.canProposeEnact(resolution("Standing Army Tax"), null, b))
        league.activeResolutions.add(ActiveResolution(5, "Arts Funding", null, null, 0))
        assertFalse(league.canProposeEnact(resolution("Arts Funding"), null, b))
    }

    @Test
    fun `a resolution with a choice can be proposed again for another choice`() {
        found()
        league.activeResolutions.add(ActiveResolution(5, "Embargo", a.civID, c.civID, 0))
        league.setTransients(gameInfo)
        assertFalse(league.canProposeEnact(resolution("Embargo"), c.civID, b))
        assertTrue(league.canProposeEnact(resolution("Embargo"), a.civID, b))
        assertFalse("not oneself", league.canProposeEnact(resolution("Embargo"), b.civID, b))
        assertFalse("a choice is needed", league.canProposeEnact(resolution("Embargo"), null, b))
    }

    @Test
    fun `an exclusive resolution is only possible once whatever the choice`() {
        val first = game.addReligion(a)
        val second = game.addReligion(b)
        for (religion in listOf(first, second))
            religion.addBeliefs(listOf(game.createBelief(BeliefType.Founder, "[+1 Food] from every [Shrine]")))
        found()
        assertEquals(setOf(first.name, second.name), league.getChoices(resolution("World Religion"), a).toSet())
        propose(a, "World Religion", first.name)
        assertFalse(league.canProposeEnact(resolution("World Religion"), second.name, b))
        league.proposals.clear()
        league.activeResolutions.add(ActiveResolution(3, "World Religion", a.civID, first.name, 0))
        assertFalse(league.canProposeEnact(resolution("World Religion"), second.name, b))
    }

    @Test
    fun `a religion that is not founded cannot be chosen`() {
        val pantheon = game.addReligion(c)
        found()
        assertFalse(pantheon.name in league.getChoices(resolution("World Religion"), a))
        assertEquals(emptyList<String>(), league.getChoices(resolution("World Religion"), a))
    }

    @Test
    fun `technologies that any civilization knows make resolutions possible`() {
        found()
        assertFalse(league.canProposeEnact(resolution("Historical Landmarks"), null, a))
        c.tech.addTechnology("Archaeology")
        assertTrue(league.canProposeEnact(resolution("Historical Landmarks"), null, a))
        assertFalse(league.canProposeEnact(resolution("Nuclear Non-Proliferation"), null, a))
    }

    @Test
    fun `the choices are the other civilizations, luxuries on the map and the ideologies`() {
        game.tileMap.tileList[3].tileResource = game.ruleset.tileResources["Silk"]!!
        found()
        assertEquals(listOf(b.civID, c.civID), league.getChoices(resolution("Embargo"), a))
        assertEquals(listOf("Silk"), league.getChoices(resolution("Ban Luxury"), a))
        assertEquals(setOf("Freedom", "Order", "Autocracy"), league.getChoices(resolution("World Ideology"), a).toSet())
        assertEquals(listOf(League.YES, League.NO), league.getChoices(ResolutionDecision.YesNo, a))
        assertEquals(listOf(a.civID, b.civID, c.civID), league.getChoices(ResolutionDecision.MajorCiv, a))
    }

    @Test
    fun `an embargo of city-states needs city-states`() {
        found()
        assertFalse(league.canProposeEnact(resolution("Embargo City-States"), null, a))
        cityState()
        assertTrue(league.canProposeEnact(resolution("Embargo City-States"), null, a))
    }

    @Test
    fun `a repeal can be proposed once for a resolution in effect`() {
        found()
        league.activeResolutions.add(ActiveResolution(5, "Arts Funding", null, null, 0))
        assertFalse(league.canProposeRepeal(6, a))
        assertFalse("c has no proposal", league.canProposeRepeal(5, c))
        assertTrue(league.canProposeRepeal(5, a))
        league.proposeRepeal(5, a)
        assertFalse(league.canProposeRepeal(5, b))
        val repeal = league.proposals.single()
        assertTrue(repeal.repeal)
        assertEquals(5, repeal.targetId)
        assertEquals("Arts Funding", repeal.resolution)
        assertFalse("the proposal of a is used", league.canPropose(a))
    }

    @Test
    fun `an embargo of a civilization that died is dropped`() {
        found()
        propose(a, "Embargo", c.civID)
        c.cities.first().destroyCity(overrideSafeties = true)
        league.doTurn()
        assertTrue(league.proposals.isEmpty())
        assertFalse(c.civID in league.members)
    }

    @Test
    fun `a host that died is replaced by the member with the most delegates`() {
        printingPress(c)
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
        assertEquals(c.civID, league.host)
        league.members.getValue(b.civID).extraDelegates = 1
        c.cities.first().destroyCity(overrideSafeties = true)
        league.doTurn()
        assertEquals(b.civID, league.host)
    }

    @Test
    fun `an embargo only blocks routes between major civilizations of the target`() {
        found()
        val cs = cityState()
        league.activeResolutions.add(ActiveResolution(5, "Embargo", a.civID, c.civID, 0))
        league.setTransients(gameInfo)
        assertTrue(league.isTradeEmbargoed(a, c))
        assertTrue(league.isTradeEmbargoed(c, a))
        assertFalse(league.isTradeEmbargoed(a, b))
        assertFalse("domestic", league.isTradeEmbargoed(c, c))
        assertFalse(league.isTradeEmbargoed(c, cs))
        league.activeResolutions.add(ActiveResolution(6, "Embargo City-States", a.civID, null, 0))
        league.setTransients(gameInfo)
        assertTrue(league.isTradeEmbargoed(b, cs))
        assertTrue(league.isTradeEmbargoed(cs, c))
    }
    //endregion

    //region Voting and passing
    private fun hostElection(vararg votes: Triple<Civilization, Int, Civilization>) =
        LeagueProposal(90, "Choose Host", false, null, null).also { proposal ->
            for ((voter, delegates, target) in votes) proposal.votes.add(LeagueVote(voter.civID, delegates, target.civID))
        }

    @Test
    fun `the host wins a tie for a civilization`() {
        found()
        assertEquals(a.civID, league.getDecision(hostElection(Triple(a, 1, c), Triple(b, 1, a))))
        league.host = c.civID
        assertEquals(c.civID, league.getDecision(hostElection(Triple(a, 2, c), Triple(b, 2, a))))
    }

    @Test
    fun `a tie without the host goes to the first civilization in order`() {
        found()
        assertEquals(b.civID, league.getDecision(hostElection(Triple(a, 1, c), Triple(c, 1, b))))
        assertEquals(b.civID, league.getDecision(hostElection(Triple(a, 1, b), Triple(c, 1, c))))
    }

    @Test
    fun `nobody wins without delegates`() {
        found()
        assertNull(league.getDecision(hostElection()))
    }

    @Test
    fun `a tie between yes and no decides nothing`() {
        found()
        val proposal = LeagueProposal(91, "Standing Army Tax", false, a.civID, null)
        proposal.votes.add(LeagueVote(a.civID, 1, "Yes"))
        proposal.votes.add(LeagueVote(b.civID, 1, "No"))
        assertNull(league.getDecision(proposal))
        proposal.votes.add(LeagueVote(c.civID, 1, "Yes"))
        assertEquals("Yes", league.getDecision(proposal))
    }

    @Test
    fun `votes are checked`() {
        found()
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        val sat = resolutionProposal("Standing Army Tax")
        assertThrows(IllegalArgumentException::class.java) { league.vote(sat.id, a, 1, "Yes") } // no session
        startSession()
        assertThrows("too many", IllegalArgumentException::class.java) { league.vote(sat.id, b, 2, "Yes") }
        assertThrows("not a choice", IllegalArgumentException::class.java) { league.vote(sat.id, b, 1, c.civID) }
        assertThrows("no proposal", IllegalArgumentException::class.java) { league.vote(404, b, 1, "Yes") }
        assertEquals(1, league.getDelegatesLeft(b))
    }

    @Test
    fun `delegates can be split over choices and proposals and merge per choice`() {
        found()
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        league.members.getValue(a.civID).extraDelegates = 1
        startSession()
        val sat = resolutionProposal("Standing Army Tax")
        val heritage = resolutionProposal("Cultural Heritage Sites")
        assertEquals(3, league.getDelegatesLeft(a))
        league.vote(sat.id, a, 1, "Yes")
        league.vote(heritage.id, a, 1, "No")
        league.vote(sat.id, a, 1, "Yes")
        league.vote(sat.id, b, 1, "Yes")
        assertEquals(0, league.getDelegatesLeft(a))
        assertFalse(league.canVote(a))
        assertEquals(mapOf("Yes" to 3), league.getTally(sat).toMap())
        assertEquals(2, sat.votes.size)

        league.vote(sat.id, c, 1, "No")
        assertEquals(mapOf("Yes" to 3, "No" to 1), league.getTally(sat).toMap())
        assertEquals(3, sat.votes.size)
        assertEquals(1, heritage.votes.size)
    }

    @Test
    fun `a resolution passes by plurality, not by majority`() {
        found()
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        startSession()
        val sat = resolutionProposal("Standing Army Tax")
        league.vote(sat.id, a, 2, "Yes")
        league.vote(sat.id, b, 1, "No")
        league.abstainRemaining(c)
        league.doTurn()

        assertFalse(league.inSession)
        assertTrue(league.isActive("Standing Army Tax"))
        assertFalse("nobody voted", league.isActive("Cultural Heritage Sites"))
        val active = league.activeResolutions.single()
        assertEquals(sat.id, active.id)
        assertEquals(a.civID, active.proposer)
        assertEquals(gameInfo.turns, active.turnEnacted)
        assertEquals(listOf("Standing Army Tax" to true, "Cultural Heritage Sites" to false), league.lastResults.map { it.resolution to it.passed })
        assertEquals(mapOf("Yes" to 2, "No" to 1), league.lastResults.first().tally.toMap())
        assertEquals(gameInfo.turns, league.lastResultsTurn)
    }

    @Test
    fun `the uniques of a resolution in effect apply to every civilization`() {
        found()
        assertFalse(b.hasUnique(com.unciv.models.ruleset.unique.UniqueType.UnitGoldMaintenancePercent))
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        startSession()
        league.vote(resolutionProposal("Standing Army Tax").id, a, 2, "Yes")
        abstainAll()
        league.doTurn()

        assertTrue(b.hasUnique(com.unciv.models.ruleset.unique.UniqueType.UnitGoldMaintenancePercent))
        assertEquals(1, league.getActiveUniques().count())
    }

    @Test
    fun `a tie or a no fails`() {
        found()
        propose(a, "Standing Army Tax")
        propose(b, "Cultural Heritage Sites")
        startSession()
        league.vote(resolutionProposal("Standing Army Tax").id, a, 1, "Yes")
        league.vote(resolutionProposal("Standing Army Tax").id, a, 1, "No")
        league.vote(resolutionProposal("Cultural Heritage Sites").id, b, 1, "No")
        league.abstainRemaining(c)
        league.doTurn()
        assertTrue(league.activeResolutions.isEmpty())
        assertTrue(league.lastResults.none { it.passed })
    }

    @Test
    fun `a session in which everybody abstains still ends`() {
        found()
        playSessionOfAbstainers()
        assertTrue(league.activeResolutions.isEmpty())
        assertEquals(2, league.lastResults.size)
    }

    @Test
    fun `a session without proposals ends at once`() {
        found()
        league.members.values.forEach { it.proposalsLeft = 0 }
        league.turnsUntilSession = 0
        league.doTurn()
        assertFalse(league.inSession)
        assertTrue(league.lastResults.isEmpty())
        assertEquals(30, league.turnsUntilSession)
        assertTrue("new proposers", league.canPropose(a))
    }

    @Test
    fun `repeals are resolved before enactments`() {
        found()
        league.activeResolutions.add(ActiveResolution(70, "Standing Army Tax", null, null, 0))
        league.nextProposalId = 71
        league.setTransients(gameInfo)
        assertTrue(a.hasUnique(com.unciv.models.ruleset.unique.UniqueType.UnitGoldMaintenancePercent))
        league.proposeRepeal(70, a)
        propose(b, "Cultural Heritage Sites")
        startSession()
        val repeal = league.proposals.first { it.repeal }
        league.vote(repeal.id, a, 1, "Yes")
        league.vote(repeal.id, a, 1, "Yes")
        league.vote(resolutionProposal("Cultural Heritage Sites").id, b, 1, "Yes")
        league.abstainRemaining(c)
        league.doTurn()

        assertFalse(league.isActive("Standing Army Tax"))
        assertTrue(league.isActive("Cultural Heritage Sites"))
        assertFalse(a.hasUnique(com.unciv.models.ruleset.unique.UniqueType.UnitGoldMaintenancePercent))
        assertEquals(listOf(true, false), league.lastResults.map { it.repeal })
    }
    //endregion

    //region World Leader
    private fun neededFor(civs: Int, cityStates: Int, deadCityStates: Int = 0): Int {
        val big = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        big.makeHexagonalMap(12)
        var cell = 0
        repeat(civs) { big.addCity(big.addCiv(), big.tileMap.tileList[7 * cell++], replacePalace = true) }
        repeat(cityStates) { big.addCity(big.addCiv(cityStateType = "Cultured"), big.tileMap.tileList[7 * cell++], replacePalace = true) }
        repeat(deadCityStates) { big.addCiv(cityStateType = "Cultured") }
        return League().also { it.setTransients(big.gameInfo) }.getVotesNeededForWorldLeader()
    }

    @Test
    fun `the votes needed for the World Leader follow the number of civilizations and city-states`() {
        val expected = listOf(Triple(2, 0, 8), Triple(2, 4, 16), Triple(4, 8, 28), Triple(6, 12, 35),
            Triple(8, 16, 40), Triple(8, 20, 44), Triple(12, 24, 47))
        for ((civs, cityStates, votes) in expected)
            assertEquals("$civs civilizations, $cityStates city-states", votes, neededFor(civs, cityStates))
    }

    @Test
    fun `defeated city-states count half`() {
        assertEquals(28, neededFor(4, 8))
        assertEquals(29, neededFor(4, 8, deadCityStates = 1))
    }

    private fun foundUnitedNations() {
        found()
        league.lastSpecialSession = "Atomic era"
        league.lastEraTrigger = "Atomic era"
        league.unitedNations = true
        league.turnsUntilSession = 10
    }

    private fun worldLeaderProposal() = league.proposals.single { it.resolution == "World Leader" }

    @Test
    fun `the United Nations alternates sessions of players with votes for the World Leader`() {
        foundUnitedNations()
        playSessionOfAbstainers()
        assertTrue("the World Leader is proposed instead of the proposals of players",
            worldLeaderProposal().let { it.proposer == null && !it.repeal })
        assertTrue(league.members.values.none { it.proposalsLeft > 0 })
        assertEquals(10, league.turnsUntilSession)

        startSession()
        assertEquals(8, league.getVotesNeededForWorldLeader())
        abstainAll()
        league.doTurn()
        assertFalse(league.inSession)
        assertTrue(league.proposals.isEmpty())
        assertTrue(league.canPropose(a) && league.canPropose(b))

        playSessionOfAbstainers()
        assertNotNull(worldLeaderProposal())
    }

    @Test
    fun `a failed vote for the World Leader gives the two leaders delegates`() {
        foundUnitedNations()
        playSessionOfAbstainers()
        startSession()
        assertEquals(listOf(6, 4, 4), listOf(a, b, c).map { league.getDelegatesLeft(it) })
        val vote = worldLeaderProposal()
        voteAll(vote, a, a.civID)
        voteAll(vote, b, b.civID)
        league.abstainRemaining(c)
        league.doTurn()

        val result = league.lastResults.single()
        assertFalse("6 of the 8 delegates needed", result.passed)
        assertFalse(a.victoryManager.hasEverWonDiplomaticVote)
        assertEquals(listOf(2, 2, 0), listOf(a, b, c).map { league.members.getValue(it.civID).extraDelegates })
        assertEquals(listOf(8, 6, 4), listOf(a, b, c).map { league.getDelegates(it) })
    }

    @Test
    fun `all civilizations that tie with the second leader get delegates`() {
        foundUnitedNations()
        league.members.values.forEach { it.proposalsLeft = 0 }
        league.proposals.add(LeagueProposal(league.nextProposalId++, "World Leader", false, null, null))
        startSession()
        val vote = worldLeaderProposal()
        league.vote(vote.id, a, 4, a.civID)
        league.vote(vote.id, a, 2, c.civID)
        league.vote(vote.id, b, 2, b.civID)
        league.abstainRemaining(b)
        league.abstainRemaining(c)
        league.doTurn()

        assertEquals(listOf(2, 2, 2), listOf(a, b, c).map { league.members.getValue(it.civID).extraDelegates })
    }

    @Test
    fun `a tie for the World Leader fails even above the votes needed`() {
        foundUnitedNations()
        league.members.values.forEach { it.proposalsLeft = 0 }
        league.members.getValue(a.civID).extraDelegates = 2
        league.members.getValue(b.civID).extraDelegates = 4
        league.proposals.add(LeagueProposal(league.nextProposalId++, "World Leader", false, null, null))
        startSession()
        assertEquals(8, league.getDelegatesLeft(a))
        assertEquals(8, league.getDelegatesLeft(b))
        val vote = worldLeaderProposal()
        voteAll(vote, a, a.civID)
        voteAll(vote, b, b.civID)
        league.abstainRemaining(c)
        league.doTurn()

        assertFalse(league.lastResults.single().passed)
        assertFalse(a.victoryManager.hasEverWonDiplomaticVote || b.victoryManager.hasEverWonDiplomaticVote)
        assertEquals(listOf(4, 6), listOf(a, b).map { league.members.getValue(it.civID).extraDelegates })
    }

    @Test
    fun `the World Leader wins the Diplomatic Victory and is not proposed again`() {
        foundUnitedNations()
        league.members.getValue(a.civID).extraDelegates = 2
        league.members.values.forEach { it.proposalsLeft = 0 }
        league.proposals.add(LeagueProposal(league.nextProposalId++, "World Leader", false, null, null))
        startSession()
        val vote = worldLeaderProposal()
        voteAll(vote, a, a.civID)
        voteAll(vote, b, a.civID)
        voteAll(vote, c, a.civID)
        league.doTurn()

        val result = league.lastResults.single()
        assertTrue(result.passed)
        assertEquals(a.civID, result.choice)
        assertTrue(a.victoryManager.hasEverWonDiplomaticVote)
        assertFalse(b.victoryManager.hasEverWonDiplomaticVote)
        assertEquals("the players propose next", 2, league.members.values.count { it.proposalsLeft > 0 })

        playSessionOfAbstainers()
        assertTrue("no World Leader a second time", league.proposals.none { it.resolution == "World Leader" })
        assertEquals(2, league.members.values.count { it.proposalsLeft > 0 })
        assertEquals(999, league.getTurnsUntilWorldLeaderSession())
    }

    @Test
    fun `the World Leader is proposed every second session in the United Nations`() {
        foundUnitedNations()
        assertEquals(10 + 10, league.getTurnsUntilWorldLeaderSession())
        playSessionOfAbstainers()
        assertEquals(10, league.getTurnsUntilWorldLeaderSession())
    }
    //endregion
}
