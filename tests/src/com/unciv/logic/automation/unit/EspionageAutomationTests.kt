package com.unciv.logic.automation.unit

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The decisions of CvEspionageAI: target numbers, the three city lists, coups, and the city-state plan */
@RunWith(GdxTestRunner::class)
class EspionageAutomationTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var aCapital: City
    private lateinit var bCapital: City
    private lateinit var ai: EspionageAutomation

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        a = game.addCiv()
        b = game.addCiv()
        aCapital = game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCapital = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        ai = EspionageAutomation(a)
    }

    private fun addSpies(count: Int): List<Spy> = List(count) { a.espionageManager.addSpy() }

    private fun setUpIn(spy: Spy, city: City) {
        spy.moveTo(city)
        var guard = 0
        while (!spy.isSetUp() && guard++ < 20) a.espionageManager.endTurn()
        assertTrue(spy.isSetUp())
    }

    // region diplomatic modifier
    @Test
    fun `offense score multiplier is 1 at war and 10 at plain peace`() {
        assertEquals(10L, ai.diplomaticModifier(b))
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(1L, ai.diplomaticModifier(b))
    }

    @Test
    fun `friends research partners and promises make spying on them less attractive`() {
        val ours = a.getDiplomacyManager(b)!!
        ours.setFlag(DiplomacyFlags.ResearchAgreement, 10)
        assertEquals(50L, ai.diplomaticModifier(b))
        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        assertEquals(2500L, ai.diplomaticModifier(b))
        b.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.AgreedToNotSendSpies, 10)
        assertEquals(250_000L, ai.diplomaticModifier(b))
    }

    @Test
    fun `a denunciation halves the multiplier and replaces the friendship factor`() {
        val ours = a.getDiplomacyManager(b)!!
        ours.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        ours.setFlag(DiplomacyFlags.Denunciation, 10)
        assertEquals(5L, ai.diplomaticModifier(b))
    }
    // endregion

    // region steal target priority
    @Test
    fun `offense score is the turns to steal at the remembered potential times the diplomatic multiplier`() {
        a.espionageManager.lastBasePotential[bCapital.id] = 3000
        // 60000 / 3000 = 20 turns, x10 at peace
        assertEquals(200L, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `without a remembered potential the offense score falls back on population`() {
        bCapital.population.setPopulation(4)
        // 60000 / (4 * 100) = 150 turns, x10
        assertEquals(1500L, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `a civilization out of technologies this turn is the worst target and an earlier turn scales the score`() {
        a.espionageManager.lastBasePotential[bCapital.id] = 3000
        game.gameInfo.turns = 40
        a.espionageManager.civOutOfTechTurn[b.civID] = 40
        assertEquals(Int.MAX_VALUE.toLong(), ai.offenseScore(bCapital, 60000))
        a.espionageManager.civOutOfTechTurn[b.civID] = 25
        assertEquals(200L * 25, ai.offenseScore(bCapital, 60000))
    }

    @Test
    fun `offense list puts the city that is quickest to rob first`() {
        val slow = game.addCity(b, game.tileMap[0, 6])
        val fast = game.addCity(b, game.tileMap[0, 7])
        a.espionageManager.lastBasePotential[bCapital.id] = 30
        a.espionageManager.lastBasePotential[slow.id] = 10
        a.espionageManager.lastBasePotential[fast.id] = 90
        val list = ai.buildOffenseCityList()
        assertEquals(listOf(fast, bCapital, slow), list)
    }

    @Test
    fun `offense list ignores unexplored cities and our own`() {
        val hidden = game.addCity(b, game.tileMap[0, 7])
        hidden.getCenterTile().setExplored(a, false)
        assertEquals(listOf(bCapital), ai.buildOffenseCityList())
    }
    // endregion

    // region defensive placement
    @Test
    fun `defense list guards the city where a thief would steal fastest`() {
        val rich = game.addCity(a, game.tileMap[0, -3])
        aCapital.cityStats.currentCityStats.science = 4f
        rich.cityStats.currentCityStats.science = 30f
        assertEquals(listOf(rich, aCapital), ai.buildDefenseCityList())
    }

    @Test
    fun `a spy that must defend goes to the best defense city`() {
        val rich = game.addCity(a, game.tileMap[0, -3])
        aCapital.cityStats.currentCityStats.science = 4f
        rich.cityStats.currentCityStats.science = 30f
        val spy = addSpies(1).single()
        ai.automateSpies()
        spy.let { assertSame(rich, it.getCityOrNull()) }
        assertEquals(SpyAction.Moving, spy.action)
    }
    // endregion

    // region spy numbers
    @Test
    fun `without a grand strategy a third of the spies sit in city-states and the rest guard when we lead in technologies`() {
        game.addCity(a, game.tileMap[0, -3])
        addSpies(3)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(1, targets.cityState)
        assertEquals(0, targets.offensive)
        assertEquals(2, targets.defensive)
        assertEquals(0, targets.diplomat)
    }

    @Test
    fun `lagging in technologies puts the spies on offense`() {
        game.addCity(b, game.tileMap[0, 6])
        for (tech in game.ruleset.technologies.keys.filter { !b.tech.isResearched(it) }.take(3)) b.tech.addTechnology(tech)
        addSpies(2)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(2, targets.offensive)
        assertEquals(0, targets.defensive)
    }

    @Test
    fun `a spaceship strategy keeps spies out of city-states`() {
        a.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        game.addCity(a, game.tileMap[0, -3])
        addSpies(3)
        assertEquals(0, ai.findTargetSpyNumbers().cityState)
    }

    @Test
    fun `spies are never more numerous than the cities they can work in`() {
        addSpies(5)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(1, targets.defensive)
        assertEquals(1, targets.offensive)
    }

    @Test
    fun `a diplomatic strategy with nobody worth a diplomat spends the spies elsewhere`() {
        a.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        addSpies(1)
        val targets = ai.findTargetSpyNumbers()
        assertEquals(0, targets.diplomat)
        assertEquals(1, targets.defensive)
    }
    // endregion

    // region coups
    private fun cityStateAllied(ally: Civilization?, y: Int): City {
        val cityState = game.addCiv(cityStateType = "Maritime")
        val city = game.addCity(cityState, game.tileMap[0, y])
        a.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.allyCiv = ally
        return city
    }

    @Test
    fun `a set up spy considers a coup in a city-state allied to someone else`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, city)
        assertTrue(ai.isCoupCandidate(spy))
    }

    @Test
    fun `no coup in an unallied city-state or in one allied to us`() {
        val free = cityStateAllied(null, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, free)
        assertFalse(ai.isCoupCandidate(spy))
        free.civ.allyCiv = a
        assertFalse(ai.isCoupCandidate(spy))
    }

    @Test
    fun `no coup against the ally of a civilization we have a declaration of friendship with`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        setUpIn(spy, city)
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 10)
        assertFalse(ai.isCoupCandidate(spy))
    }

    @Test
    fun `a spy still on its way is no coup candidate`() {
        val city = cityStateAllied(b, -5)
        val spy = addSpies(1).single()
        spy.moveTo(city)
        assertFalse(ai.isCoupCandidate(spy))
    }
    // endregion

    // region city-state plan
    @Test
    fun `before the United Nations countdown the plan is to play normal`() {
        val plan = ai.getCityStatePlan()
        assertEquals(EspionageAutomation.CityStatePlanKind.PlayNormal, plan.kind)
    }

    @Test
    fun `a rival allied with half of the city-states is attacked`() {
        repeat(2) { cityStateAllied(b, -4 - it) }
        a.espionageManager.unCountdownStarted = true
        val plan = ai.getCityStatePlan()
        assertEquals(EspionageAutomation.CityStatePlanKind.AttackToPreventDefeat, plan.kind)
        assertSame(b, plan.diploThreat)
    }

    @Test
    fun `the civilization allied with half of the city-states defends its lead`() {
        repeat(2) { cityStateAllied(b, -4 - it) }
        b.espionageManager.unCountdownStarted = true
        assertEquals(EspionageAutomation.CityStatePlanKind.DefendForWin, EspionageAutomation(b).getCityStatePlan().kind)
    }

    @Test
    fun `a quarter of the city-states makes us collect votes`() {
        repeat(4) { cityStateAllied(if (it == 0) b else null, -4 - it) }
        a.espionageManager.unCountdownStarted = true
        assertEquals(EspionageAutomation.CityStatePlanKind.CollectVotes, ai.getCityStatePlan().kind)
    }

    @Test
    fun `the city-state to attack is the one allied to the threat with the least influence`() {
        val city = cityStateAllied(b, -5)
        val plan = EspionageAutomation.CityStatePlan(EspionageAutomation.CityStatePlanKind.AttackToPreventDefeat, b)
        val allied = ai.minorCityScore(city.civ, plan, 0)
        val other = cityStateAllied(null, -6)
        assertTrue(allied > ai.minorCityScore(other.civ, plan, 0))
        assertEquals(100L, ai.minorCityScore(other.civ, plan, 0))
    }
    // endregion
}
