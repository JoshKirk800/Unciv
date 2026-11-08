package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteManager
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The decisions of CvTradeAI: candidate connections, their scores, their order, and the choice of a trade unit */
@RunWith(GdxTestRunner::class)
class TradeRouteAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit
    private lateinit var origin: City

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(16)
        civA = game.addCiv("[+4] Trade Route slots")
        civB = game.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.makePeace()
        caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
        origin = game.addCity(civA, game.tileMap[0, 0])
    }

    /** Gives civB technologies we lack: with equal cities the gold delta is 0 and only the technology delta makes a connection worth taking */
    private fun giveCivBTechs() {
        for (tech in game.ruleset.technologies.keys.filter { !civB.tech.isResearched(it) }.take(3)) civB.tech.addTechnology(tech)
    }

    private fun cityOf(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y])

    private fun candidateTo(destination: City) =
        TradeRouteAutomation.getAvailableRoutes(civA).first { it.destination.city == destination && it.destination.kind == TradeRouteKind.International }

    @Test
    fun `candidates are the connections in range that can be started`() {
        val near = cityOf(civB, 6)
        val far = cityOf(civB, 12)
        val destinations = TradeRouteAutomation.getAvailableRoutes(civA).map { it.destination.city }
        assertTrue(near in destinations)
        assertFalse(far in destinations)
    }

    @Test
    fun `a connection already carrying a route is still a candidate`() {
        val near = cityOf(civB, 6)
        val unit = game.addUnit(caravan.name, civA, origin.getCenterTile())
        civA.tradeRoutes.establish(unit, near, TradeRouteKind.International)!!
        assertTrue(TradeRouteAutomation.getAvailableRoutes(civA).any { it.destination.city == near })
    }

    @Test
    fun `an international route with a gain scores positive and a plundered one scores nothing`() {
        val near = cityOf(civB, 6)
        giveCivBTechs()
        val danger = PlotDanger(civA)
        val candidate = candidateTo(near)
        assertTrue(TradeRouteAutomation.scoreInternational(civA, candidate, danger) > 0)
        civA.tradeRoutes.plunderedConnections[TradeRouteManager.plunderKey(origin.location, near.location)] = game.gameInfo.turns
        assertEquals(0, TradeRouteAutomation.scoreInternational(civA, candidate, danger))
    }

    @Test
    fun `routes through enemy land are penalised by a thousand per tile`() {
        val near = cityOf(civB, 6)
        val danger = PlotDanger(civA)
        val path = listOf(game.tileMap[0, 10].also { it.setOwningCity(near) })
        val atPeace = TradeRouteAutomation.dangerSum(civA, path, danger)
        civA.getDiplomacyManager(civB)!!.declareWar()
        val atWar = TradeRouteAutomation.dangerSum(civA, path, PlotDanger(civA))
        assertEquals(1000, atWar - atPeace)
    }

    @Test
    fun `a longer path through unseen tiles is more dangerous`() {
        val path = List(3) { game.tileMap[0, it + 10] }
        assertEquals(1 + 3, TradeRouteAutomation.dangerSum(civA, path, PlotDanger(civA)))
    }

    @Test
    fun `technology difference counts what the other knows and we lack, and is zero with city-states`() {
        val techs = game.ruleset.technologies.keys.filter { !civB.tech.isResearched(it) }.take(3)
        for (tech in techs) civB.tech.addTechnology(tech)
        assertEquals(3, TradeRouteAutomation.techDifference(civA, civB))
        assertEquals(0, TradeRouteAutomation.techDifference(civB, civA))
        val cityState = game.addCiv(cityStateType = "Maritime")
        assertEquals(0, TradeRouteAutomation.techDifference(civA, cityState))
    }

    @Test
    fun `international candidates are ordered with the lowest score first as in the shipped comparator`() {
        // City-states: a route that gives us less than nothing still scores 1, so every path is a candidate
        for (y in listOf(5, 7, 9)) {
            val cityState = game.addCiv(cityStateType = "Maritime")
            civA.diplomacyFunctions.makeCivilizationsMeet(cityState)
            cityOf(cityState, y)
        }
        val danger = PlotDanger(civA)
        val ordered = TradeRouteAutomation.prioritizeTradeRoutes(civA).filter { it.destination.kind == TradeRouteKind.International }
        val scores = ordered.map { TradeRouteAutomation.scoreInternational(civA, it, danger) }
        assertTrue("$scores", scores.size >= 3)
        assertTrue("$scores", scores.all { it > 0 })
        assertEquals(scores.sorted(), scores)
    }

    @Test
    fun `a trade unit waiting in its origin city takes the first connection of its domain`() {
        val near = cityOf(civB, 6)
        giveCivBTechs()
        val unit = game.addUnit(caravan.name, civA, origin.getCenterTile())
        TradeRouteAutomation.automateTradeUnit(unit)
        val route = civA.tradeRoutes.routes.single()
        assertEquals(near.location, route.destination)
    }
}
