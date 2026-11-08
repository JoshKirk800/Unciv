package com.unciv.logic.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.unit.EspionageAutomation
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.league.League
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class DiplomatTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var third: Civilization
    private lateinit var spy: Spy

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        a = game.addCiv()
        b = game.addCiv()
        third = game.addCiv()
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        game.addCity(third, game.tileMap[0, -5], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        spy = a.espionageManager.addSpy()
    }

    private fun becomeDiplomat(target: Civilization = b) {
        spy.moveTo(target.getCapital()!!, asDiplomat = true)
        assertEquals(SpyAction.Moving, spy.action)
        assertNull(a.espionageManager.getActiveDiplomatFor(target))
        a.espionageManager.endTurn()
        assertEquals(SpyAction.MakingIntroductions, spy.action)
        assertNull(a.espionageManager.getActiveDiplomatFor(target))
        repeat(spy.turnsRemainingForAction) { a.espionageManager.endTurn() }
        assertEquals(SpyAction.Schmoozing, spy.action)
        assertSame(spy, a.espionageManager.getActiveDiplomatFor(target))
    }

    private fun makeTenTourism() {
        val capital = a.getCapital()!!
        val gallery = game.createBuilding("Has [5] [Art/Artifact] Great Work slots")
        capital.cityConstructions.addBuilding(gallery)
        repeat(5) {
            val work = game.createGreatWork()
            capital.greatWorks.place(GreatWorkInstance(work.name, a.civName, "Ancient era", 1), GreatWorkSlot(capital, gallery, it))
        }
    }

    @Test
    fun `diplomat requires an explored peaceful foreign major capital and switches roles`() {
        val otherCity = game.addCity(b, game.tileMap[2, 4])
        assertFalse(spy.canMoveTo(otherCity, asDiplomat = true))
        assertFalse(spy.canMoveTo(a.getCapital()!!, asDiplomat = true))
        b.getCapital()!!.getCenterTile().setExplored(a, false)
        assertFalse(spy.canMoveTo(b.getCapital()!!, asDiplomat = true))
        b.getCapital()!!.getCenterTile().setExplored(a, true)
        assertTrue(spy.canMoveTo(b.getCapital()!!, asDiplomat = true))
        becomeDiplomat()
        spy.moveTo(b.getCapital()!!)
        assertFalse(spy.isDiplomat)
        assertNull(a.espionageManager.getActiveDiplomatFor(b))
        assertEquals(SpyAction.Moving, spy.action)
        spy.moveTo(null)
        assertFalse(spy.isDiplomat)
    }

    @Test
    fun `a schmoozing diplomat counters different ideology and adds a Globalization delegate only after research`() {
        makeTenTourism()
        for (civ in listOf(a, b)) {
            civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        }
        a.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        b.policies.adopt(game.ruleset.policyBranches["Order"]!!)
        val baseline = a.culture.getInfluencePerTurn(b)
        assertEquals(6, baseline)
        assertEquals(3, a.culture.getTourismIncreaseFromTradeConnection(b))
        val league = League().also { it.setTransients(game.gameInfo); it.lastSpecialSession = "Renaissance era" }
        game.gameInfo.league = league
        val delegates = league.getDelegates(a)
        becomeDiplomat()
        assertEquals(9, a.culture.getInfluencePerTurn(b)) // (100 - 34 + 25)% of 10, truncated
        assertEquals(2, a.culture.getTourismIncreaseFromTradeConnection(b))
        assertEquals(delegates, league.getDelegates(a))
        a.tech.addTechnology("Globalization")
        assertEquals(delegates + 1, league.getDelegates(a))
        spy.moveTo(null)
        assertEquals(baseline, a.culture.getInfluencePerTurn(b))
        assertEquals(delegates, league.getDelegates(a))
    }

    @Test
    fun `AI going for a diplomatic victory makes a diplomat of a spy once the other civilizations are worth a whole one`() {
        // CvEspionageAI::FindTargetSpyNumbers: Globalization makes each other civilization worth 0.25 diplomats, floored after summing
        a.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        game.addCity(game.addCiv(), game.tileMap[5, 0], replacePalace = true)
        game.addCity(game.addCiv(), game.tileMap[-5, 0], replacePalace = true)
        game.gameInfo.league = League().also { it.setTransients(game.gameInfo) }
        a.tech.addTechnology("Globalization")
        val spare = a.espionageManager.addSpy()
        assertEquals(1, EspionageAutomation(a).findTargetSpyNumbers().diplomat)
        EspionageAutomation(a).automateSpies()
        assertSame(b.getCapital(), spy.getCityOrNull())
        assertTrue(spy.isDiplomat)
        assertEquals(SpyAction.Moving, spy.action)
        assertFalse(spare.isDiplomat)
    }

    @Test
    fun `AI without a diplomatic or culture strategy sends no diplomats`() {
        game.gameInfo.league = League().also { it.setTransients(game.gameInfo) }
        a.tech.addTechnology("Globalization")
        a.espionageManager.addSpy()
        assertEquals(0, EspionageAutomation(a).findTargetSpyNumbers().diplomat)
    }

    @Test
    fun `a diplomat without opposing ideologies gives no tourism bonus`() {
        makeTenTourism()
        becomeDiplomat()
        assertEquals(10, a.culture.getInfluencePerTurn(b))
    }

    @Test
    fun `familiarity shortens surveillance and increases spy skill without changing persisted rank`() {
        val capital = b.getCapital()!!
        assertEquals(3, a.culture.getSurveillanceTurns(b))
        spy.moveTo(capital)
        a.espionageManager.endTurn()
        assertEquals(3, spy.turnsRemainingForAction)
        spy.moveTo(null)
        b.totalCultureForContests = 1000
        a.culture.addInfluence(b, 2000) // Dominant: two extra effective ranks, capped at max spy rank
        assertEquals(1, a.culture.getSurveillanceTurns(b))
        spy.moveTo(capital)
        a.espionageManager.endTurn()
        assertEquals(SpyAction.EstablishNetwork, spy.action)
        assertEquals(1, spy.turnsRemainingForAction)
        assertEquals(1, spy.rank)
        assertEquals(3, spy.getEffectiveRank())
    }

    @Test
    fun `diplomat survives clone and invalid assignments cease providing bonuses`() {
        becomeDiplomat()
        val copiedManager = a.espionageManager.clone().also { it.setTransients(a) }
        val copy = copiedManager.spyList.single()
        assertTrue(copy.isDiplomat)
        assertEquals(SpyAction.Schmoozing, copy.action)
        assertSame(b.getCapital(), copy.getCityOrNull())
        a.getDiplomacyManager(b)!!.declareWar()
        assertNull(a.espionageManager.getActiveDiplomatFor(b))
        a.espionageManager.endTurn()
        assertTrue(spy.isIdle())
        assertFalse(spy.isDiplomat)
    }
}
