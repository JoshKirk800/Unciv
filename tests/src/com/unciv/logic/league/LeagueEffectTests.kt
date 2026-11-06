package com.unciv.logic.league

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.tile.TileResource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The effects of World Congress resolutions on the rest of the engine (hooks of [LeagueEffects]) */
@RunWith(GdxTestRunner::class)
class LeagueEffectTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var civC: Civilization
    private lateinit var caravan: BaseUnit
    private var nextId = 1

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(16)
        civA = game.addCiv("[+4] Trade Route slots")
        civB = game.addCiv("[+4] Trade Route slots")
        civC = game.addCiv("[+4] Trade Route slots")
        for ((one, other) in listOf(civA to civB, civA to civC, civB to civC)) {
            one.diplomacyFunctions.makeCivilizationsMeet(other)
            one.getDiplomacyManager(other)!!.makePeace()
        }
        caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        for (tile in game.tileMap.values) for (civ in listOf(civA, civB, civC)) tile.setExplored(civ, true)
    }

    //region helpers
    /** Puts the resolution [name] into effect with [choice], as the League does after a vote */
    private fun enact(name: String, choice: String? = null): ActiveResolution {
        val league = game.gameInfo.league ?: League().also { game.gameInfo.league = it }
        val active = ActiveResolution(nextId++, name, null, choice, game.gameInfo.turns)
        league.activeResolutions.add(active)
        league.setTransients(game.gameInfo)
        LeagueEffects.onEnact(league, active)
        return active
    }

    private fun cityAt(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y])

    private fun establish(origin: City, destination: City, kind: TradeRouteKind = TradeRouteKind.International): Boolean {
        val unit = game.addUnit(caravan.name, origin.civ, origin.getCenterTile())
        val route = origin.civ.tradeRoutes.establish(unit, destination, kind)
        if (route == null) unit.destroy()
        return route != null
    }

    private fun waitingUnits(civ: Civilization) = civ.units.getCivUnits().count { it.name == caravan.name }

    /** A luxury on the centre tile of a new city of [civ], where it is connected without an improvement */
    private fun addLuxury(civ: Civilization): TileResource {
        val resource = game.ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury }[luxuryCities]
        val city = game.addCity(civ, game.tileMap[luxuryCities++ + 3, 0])
        city.getCenterTile().tileResource = resource
        city.getCenterTile().resourceAmount = 1
        // a resource under a city needs the tech of an improvement that can extract it
        for (improvement in resource.getImprovements())
            game.ruleset.tileImprovements[improvement]?.techRequired?.let { civ.tech.addTechnology(it) }
        civ.cache.updateCivResources()
        return resource
    }
    private var luxuryCities = 0
    //endregion

    //region Standing Army Tax, Scholars In Residence
    private fun upkeepOf(civ: Civilization) = -civ.stats.getStatMapForNextTurn()["Unit upkeep"]!!.gold

    @Test
    fun `standing army tax raises unit maintenance by its percent`() {
        cityAt(civA, 0)
        val soldier = game.createBaseUnit("Civilian")
        for (i in 0 until 83) game.addUnit(soldier.name, civA, game.tileMap[i % 9 - 4, i / 9 - 4])
        val before = upkeepOf(civA)
        assertTrue(before > 0f)
        enact("Standing Army Tax")
        val after = upkeepOf(civA)
        assertEquals(before * 1.25f, after, 1f) // both truncated to whole gold
        assertTrue(after > before)
    }

    @Test
    fun `scholars in residence make only technologies another civilization knows cheaper`() {
        cityAt(civA, 0)
        cityAt(civB, 5)
        val known = "Agriculture"
        val unknown = "Pottery"
        civB.tech.addTechnology(known)
        val knownBefore = civA.tech.costOfTech(known)
        val unknownBefore = civA.tech.costOfTech(unknown)
        enact("Scholars In Residence")
        assertEquals(knownBefore / 1.2f, civA.tech.costOfTech(known).toFloat(), 2f)
        assertTrue(civA.tech.costOfTech(known) < knownBefore)
        assertEquals(unknownBefore, civA.tech.costOfTech(unknown))
    }

    @Test
    fun `scholars in residence do not count our own knowledge`() {
        cityAt(civA, 0)
        cityAt(civB, 5)
        civA.tech.addTechnology("Pottery")
        val before = civA.tech.costOfTech("Pottery")
        enact("Scholars In Residence")
        assertEquals("civB does not know it, and we do not count", before, civA.tech.costOfTech("Pottery"))
    }
    //endregion

    //region Embargoes
    @Test
    fun `an embargo blocks new routes both ways and keeps own routes legal`() {
        val a = cityAt(civA, 0)
        val b1 = cityAt(civB, 5)
        val b2 = cityAt(civB, 8)
        val c = cityAt(civC, -5)
        b1.cityConstructions.addBuilding(game.createBuilding("Allows [Food] trade routes between your cities"))
        assertTrue(civA.tradeRoutes.canConnect(a, b1, TradeRouteKind.International))

        enact("Embargo", civB.civID)

        assertFalse(civA.tradeRoutes.canConnect(a, b1, TradeRouteKind.International))
        assertFalse(civB.tradeRoutes.canConnect(b1, a, TradeRouteKind.International))
        assertTrue("other civs are not affected", civA.tradeRoutes.canConnect(a, c, TradeRouteKind.International))
        assertTrue("domestic routes of the target stay legal", civB.tradeRoutes.canConnect(b1, b2, TradeRouteKind.Food))
    }

    @Test
    fun `enacting an embargo ends the routes of the target and returns the unit`() {
        val a = cityAt(civA, 0)
        val b1 = cityAt(civB, 5)
        val b2 = cityAt(civB, 8)
        val c = cityAt(civC, -5)
        b1.cityConstructions.addBuilding(game.createBuilding("Allows [Food] trade routes between your cities"))
        assertTrue(establish(a, b1))
        assertTrue(establish(b1, a))
        assertTrue(establish(a, c))
        assertTrue(establish(b1, b2, TradeRouteKind.Food))
        assertEquals(0, waitingUnits(civA))
        assertEquals(0, waitingUnits(civB))

        enact("Embargo", civB.civID)

        assertEquals("only the route to civ C is left", listOf(c.location), civA.tradeRoutes.routes.map { it.destination })
        assertEquals("only the domestic route is left", listOf(b2.location), civB.tradeRoutes.routes.map { it.destination })
        assertEquals(1, waitingUnits(civA))
        assertEquals(1, waitingUnits(civB))
        assertEquals(0, waitingUnits(civC))
        assertNotNull(civB.tradeRoutes.routes.firstOrNull())
    }

    @Test
    fun `the city-state embargo ends only the routes to city-states`() {
        val cityStateType = game.ruleset.cityStateTypes.keys.first()
        val cityState = game.addCiv(cityStateType = cityStateType)
        civA.diplomacyFunctions.makeCivilizationsMeet(cityState)
        civA.getDiplomacyManager(cityState)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
        val a = cityAt(civA, 0)
        val b = cityAt(civB, 5)
        val cs = cityAt(cityState, -5)
        assertTrue(establish(a, b))
        assertTrue(establish(a, cs))
        assertEquals(0, waitingUnits(civA))

        enact("Embargo City-States")

        assertEquals(listOf(b.location), civA.tradeRoutes.routes.map { it.destination })
        assertEquals(1, waitingUnits(civA))
        assertFalse(civA.tradeRoutes.canConnect(a, cs, TradeRouteKind.International))
        assertTrue(civA.tradeRoutes.canConnect(a, cityAt(civC, 2), TradeRouteKind.International))
    }
    //endregion

    //region Ban Luxury
    @Test
    fun `a banned luxury gives no happiness`() {
        cityAt(civA, 0)
        val luxury = addLuxury(civA)
        val other = addLuxury(civA)
        val before = civA.stats.getHappinessBreakdown()["Luxury resources"]!!
        assertTrue(before > 0f)

        enact("Ban Luxury", luxury.name)
        assertEquals("the other luxury still counts", before / 2, civA.stats.getHappinessBreakdown()["Luxury resources"]!!, 0.001f)

        enact("Ban Luxury", other.name)
        assertEquals(0f, civA.stats.getHappinessBreakdown()["Luxury resources"]!!, 0.001f)
    }

    @Test
    fun `a banned luxury cannot be traded`() {
        cityAt(civA, 0)
        cityAt(civB, 5)
        val luxury = addLuxury(civA)
        val offer = TradeOffer(luxury.name, TradeOfferType.Luxury_Resource, 1, speed = game.gameInfo.speed)
        val trade = Trade().apply { ourOffers.add(offer) }
        assertTrue(TradeLogic(civA, civB).ourAvailableOffers.any { it.name == luxury.name })
        assertTrue(TradeEvaluation().isTradeValid(trade, civA, civB))

        enact("Ban Luxury", luxury.name)

        assertFalse(TradeLogic(civA, civB).ourAvailableOffers.any { it.name == luxury.name })
        assertFalse(TradeEvaluation().isTradeValid(trade, civA, civB))
    }
    //endregion

    //region World Religion, World Ideology
    @Test
    fun `world religion raises the tourism of the holy city of the chosen religion only`() {
        val holy = cityAt(civA, 0)
        val other = cityAt(civB, 8)
        val religion = game.addReligion(civA)
        holy.religion.religionThisIsTheHolyCityOf = religion.name
        for (city in listOf(holy, other))
            city.cityConstructions.addBuilding(game.createBuilding("[+10] Tourism [in this city]"))
        assertEquals(10, civA.culture.getCityTourism(holy))

        enact("World Religion", religion.name)

        assertEquals(15, civA.culture.getCityTourism(holy))
        assertEquals(10, civB.culture.getCityTourism(other))
    }

    @Test
    fun `world religion strengthens the pressure of the chosen religion`() {
        val source = cityAt(civA, 0)
        val target = cityAt(civB, 5)
        val religion = game.addReligion(civA)
        religion.addBelief(game.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        source.religion.addPressure(religion.name, 1000)
        val base = target.religion.getPressuresFromSurroundingCities()[religion.name]
        assertTrue(base > 0)

        enact("World Religion", religion.name)

        assertEquals((base * 1.25f).toInt(), target.religion.getPressuresFromSurroundingCities()[religion.name])
    }

    @Test
    fun `world ideology presses on every civilization`() {
        cityAt(civA, 0)
        val (before, _) = civA.culture.publicOpinion.getPressureOnUs()
        assertEquals(0, before["Freedom"])

        enact("World Ideology", "Freedom")

        val (total, byCiv) = civA.culture.publicOpinion.getPressureOnUs()
        assertEquals(2, total["Freedom"])
        assertEquals(0, total["Order"])
        assertEquals(2, byCiv["Freedom"]!!["World Congress"])
    }
    //endregion
}
