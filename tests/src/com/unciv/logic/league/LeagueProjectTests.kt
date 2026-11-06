package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The League projects: World's Fair, International Games, International Space Station (CvVotingClasses.cpp:3583-3808, 6008-6232) */
@RunWith(GdxTestRunner::class)
class LeagueProjectTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var league: League
    private var nextCell = 0

    private val gameInfo get() = game.gameInfo
    private val fair = "World's Fair"
    private val games = "International Games"
    private val station = "International Space Station"

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        gameInfo.speed = speedOf(1f)
        gameInfo.gameParameters.startingEra = "Ancient era"
        a = major(isPlayer = true)
        b = major()
        c = major()
        val majors = listOf(a, b, c)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    /** A speed of our own: the ones of the ruleset are shared with other tests */
    private fun speedOf(percent: Float) = Speed().apply {
        name = "Test"; productionCostModifier = percent
        turns = arrayListOf(hashMapOf("yearsPerTurn" to 1f, "untilTurn" to 500f))
    }

    private fun major(isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer)
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    private fun start(name: String): LeagueProject {
        league.startProject(game.ruleset.resolutions[name]!!)
        return league.getProject(name)!!
    }

    private fun contribute(project: LeagueProject, civ: Civilization, amount: Int) = project.contributions.add(civ.civID, amount)

    @Test
    fun `the cost is the share per civilization times speed, start era and the major civilizations`() {
        assertEquals(350, league.getProjectCostPerPlayer(fair))
        assertEquals(350 * 3, league.getProjectCost(fair))
        assertEquals(720 * 3, league.getProjectCost(games))
        assertEquals(850 * 3, league.getProjectCost(station))
        gameInfo.gameParameters.startingEra = "Renaissance era" // 55 percent
        assertEquals(192, league.getProjectCostPerPlayer(fair)) // 350 * 0.55 = 192.5, truncated
        gameInfo.speed = speedOf(1.5f)
        assertEquals(288, league.getProjectCostPerPlayer(fair)) // 350 * 1.5 * 0.55 = 288.75, truncated
    }

    @Test
    fun `only major members can contribute and only while the project runs`() {
        assertFalse(league.canContribute(a, fair))
        val project = start(fair)
        assertTrue(league.canContribute(a, fair))
        assertFalse("not started", league.canContribute(a, games))
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        assertFalse(league.canContribute(cityState, fair))
        project.complete = true
        assertFalse(league.canContribute(a, fair))
    }

    @Test
    fun `the production of a city that builds the project is the contribution`() {
        val city = a.cities.first()
        val constructions = city.cityConstructions
        assertFalse("not buildable before the project", PerpetualConstruction.WorldsFair.isBuildable(constructions))
        val project = start(fair)
        assertTrue(PerpetualConstruction.WorldsFair.isBuildable(constructions))
        assertFalse(PerpetualConstruction.InternationalGames.isBuildable(constructions))
        constructions.addToQueue(fair)
        assertEquals(fair, constructions.currentConstructionName())

        constructions.endTurn(Stats(production = 10f))
        constructions.endTurn(Stats(production = 7f))
        assertEquals(17, project.contributions[a.civID])
        assertEquals(0, project.contributions[b.civID])
        assertEquals(17, league.getProjectProgress(project))
    }

    @Test
    fun `tiers begin at half and the whole share, the top one needs the best contribution`() {
        val project = start(fair) // share 350
        assertEquals(listOf(175, 350, 350), (1..3).map { league.getTierThreshold(project, it) })
        contribute(project, a, 174)
        contribute(project, b, 349)
        assertEquals(0, league.getContributionTier(project, a))
        assertEquals(1, league.getContributionTier(project, b))
        contribute(project, a, 1)
        contribute(project, b, 1)
        assertEquals(1, league.getContributionTier(project, a))
        assertEquals(3, league.getContributionTier(project, b)) // the best, and at least a whole share

        contribute(project, a, 500) // 675 is now the best: the top threshold follows it
        assertEquals(675, league.getTierThreshold(project, 3))
        assertEquals(3, league.getContributionTier(project, a))
        assertEquals(2, league.getContributionTier(project, b))
    }

    @Test
    fun `of civilizations that tie for the top only the first member gets tier 3`() {
        val project = start(fair)
        contribute(project, b, 400)
        contribute(project, c, 400)
        assertEquals(3, league.getContributionTier(project, b))
        assertEquals(2, league.getContributionTier(project, c))
        assertEquals(0, league.getContributionTier(project, a))
    }

    @Test
    fun `a project that has its cost together is complete and rewards each tier cumulatively`() {
        val project = start(fair) // cost 1050
        contribute(project, a, 500)
        contribute(project, b, 175)
        contribute(project, c, 350)
        league.checkProjects()
        assertFalse(project.complete)
        assertTrue("33 percent were reached", project.warned)
        val policiesBefore = listOf(a, b, c).map { it.policies.freePolicies }

        contribute(project, a, 100) // 1125 together
        league.checkProjects()
        assertTrue(project.complete)

        // a: tier 3 (best, 600 >= 350), c: tier 2, b: tier 1
        assertEquals(500, a.goldenAges.storedHappiness)
        assertEquals(500, b.goldenAges.storedHappiness)
        assertEquals(500, c.goldenAges.storedHappiness)
        assertEquals(listOf(1, 0, 1), listOf(a, b, c).mapIndexed { i, civ -> civ.policies.freePolicies - policiesBefore[i] })
        assertEquals(1, a.temporaryUniques.size)
        assertEquals(20, a.temporaryUniques.first().turnsLeft)
        assertEquals("[+100]% [Culture] <upon reaching contribution tier [3] of a World Congress project>", a.temporaryUniques.first().unique.replace("  ", " "))
        assertTrue(b.temporaryUniques.isEmpty() && c.temporaryUniques.isEmpty())
    }

    @Test
    fun `a complete project gives nothing twice and cannot be started or proposed again`() {
        val project = start(fair)
        contribute(project, a, 1050)
        league.checkProjects()
        assertTrue(project.complete)
        assertEquals(500, a.goldenAges.storedHappiness)

        league.checkProjects()
        league.startProject(game.ruleset.resolutions[fair]!!)
        assertEquals(500, a.goldenAges.storedHappiness)
        assertEquals(1, league.projects.size)
        assertFalse(league.canContribute(a, fair))
        assertFalse(league.canProposeEnact(game.ruleset.resolutions[fair]!!, null, a))
    }

    @Test
    fun `a project is proposed once, voted for and then runs without being a resolution in effect`() {
        val resolution = game.ruleset.resolutions[fair]!!
        assertTrue(league.canProposeEnact(resolution, null, a))
        league.proposeEnact(resolution, null, a)
        assertFalse("already on the table", league.canProposeEnact(resolution, null, a))
        for (civ in listOf(b, c)) while (league.canPropose(civ)) {
            val other = league.getProposableResolutions(civ).first { it.name != fair }
            league.proposeEnact(other, if (other.proposerDecision == ResolutionDecision.None) null else league.getChoices(other, civ).first(), civ)
        }
        league.turnsUntilSession = 0
        league.doTurn()
        assertTrue(league.inSession)
        val proposal = league.proposals.first { it.resolution == fair }
        for (civ in listOf(a, b, c)) {
            if (league.canVote(civ)) league.vote(proposal.id, civ, league.getDelegatesLeft(civ), League.YES)
        }
        league.doTurn()
        assertFalse(league.inSession)
        assertNotNull(league.getProject(fair))
        assertTrue(league.isProjectActive(fair))
        assertTrue(league.activeResolutions.none { it.resolution == fair })
        assertFalse(league.canProposeEnact(resolution, null, a))
    }

    @Test
    fun `the International Games give permanent happiness, influence with city-states, a policy and tourism`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val influenceBefore = cityState.getDiplomacyManager(a)!!.getInfluence()
        val happinessBefore = { a.updateStatsForNextTurn(); a.getHappiness() }()
        val policiesBefore = a.policies.freePolicies

        val project = start(games)
        contribute(project, a, 720 * 3)
        league.checkProjects()

        assertTrue(project.complete)
        assertEquals(influenceBefore + 30f, cityState.getDiplomacyManager(a)!!.getInfluence(), 0.01f)
        assertEquals(policiesBefore + 1, a.policies.freePolicies)
        assertEquals(listOf(-1, -1), a.temporaryUniques.filter { it.unique == "[+3 Happiness]" }.map { it.turnsLeft })
        assertEquals(1, a.temporaryUniques.count { it.unique.startsWith("[+100]% Tourism") && it.turnsLeft == 20 })
        a.updateStatsForNextTurn()
        assertEquals(happinessBefore + 6, a.getHappiness())

        repeat(30) { a.temporaryUniques.endTurn() }
        assertEquals("the happiness is kept, the tourism is not", 2, a.temporaryUniques.size)
    }

    @Test
    fun `the Space Station gives its building to the top contributor only, which cannot be built otherwise`() {
        val project = start(station)
        contribute(project, a, 1700)
        contribute(project, b, 850)
        contribute(project, c, 100)
        league.checkProjects()
        assertTrue(project.complete)
        val building = "International Space Station Complex"
        assertTrue(a.getCapital()!!.cityConstructions.isBuilt(building))
        assertFalse(b.getCapital()!!.cityConstructions.isBuilt(building))
        assertFalse(c.getCapital()!!.cityConstructions.isBuilt(building))
        // tier 2 gets a Great Scientist, the top one too (cumulative)
        assertEquals(1, a.units.getCivUnits().count { it.name == "Great Scientist" })
        assertEquals(1, b.units.getCivUnits().count { it.name == "Great Scientist" })
        assertEquals(0, c.units.getCivUnits().count { it.name == "Great Scientist" })

        val ruleBuilding = game.ruleset.buildings[building]!!
        for (civ in listOf(a, b, c)) assertFalse(ruleBuilding.isBuildable(civ.cities.first().cityConstructions))
    }

    @Test
    fun `a clone keeps the projects`() {
        val project = start(fair)
        contribute(project, a, 40)
        project.warned = true
        val clone = league.clone().getProject(fair)!!
        assertEquals(40, clone.contributions[a.civID])
        assertTrue(clone.warned)
        assertFalse(clone === project)
    }
}
