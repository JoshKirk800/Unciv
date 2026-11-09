package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The AI's plunder of trade routes (CvTacticalAI::FindTacticalTargets' trade branches, PlotPlunderTradeUnitMoves, ExecutePlunderTradeUnit,
 *  PlotPlunderTradePlotMoves, the barbarians' PlotBarbarianPlunderTradeUnitMove and FindBarbarianGankTradeRouteTarget) with Brave New World data.
 *  A route of [victim] to [partner] runs along x = 0; [civ] is at war with [victim] only.
 */
@RunWith(BaseTestRunner::class)
class TradeMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var victim: Civilization
    private lateinit var partner: Civilization
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(20)
        victim = game.addCiv("[+4] Trade Route slots")
        partner = game.addCiv()
        civ = game.addCiv()
        victim.diplomacyFunctions.makeCivilizationsMeet(partner)
        victim.getDiplomacyManager(partner)!!.makePeace()
        victim.diplomacyFunctions.makeCivilizationsMeet(civ)
        victim.getDiplomacyManager(civ)!!.declareWar()
        for (tile in game.tileMap.values) for (c in listOf(victim, partner, civ)) tile.setExplored(c, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun route(distance: Int = 8, unit: String = "Caravan"): TradeRoute {
        val origin = game.addCity(victim, tile(0))
        val destination = game.addCity(partner, tile(distance))
        val trader = game.addUnit(unit, victim, origin.getCenterTile())
        return victim.tradeRoutes.establish(trader, destination, TradeRouteKind.International)!!
    }

    private fun see(c: Civilization = civ) {
        c.viewableTiles = game.tileMap.values.toSet()
        c.ai.invalidateTurnCaches()
    }

    private fun context(c: Civilization = civ): TacticalContext {
        see(c)
        val map = if (c.isBarbarian) null else c.ai.tactical.map
        val danger = c.ai.plotDanger
        return TacticalContext(c, map, danger, AutomationCache(), c.units.getCivUnits().toList(), TacticalTargets.find(c, map, danger))
    }

    /** A tile next to the trader that the route does not cross */
    private fun besideTrader(route: TradeRoute): Tile = route.getCurrentTile().neighbors.first { it !in route.getTiles() }

    // region Targets

    @Test
    fun `a trader of a civilization at war with us is a target where it stands, its route tiles are parking targets, and at peace there are none`() {
        val route = route()
        val ctx = context()
        val trader = route.getCurrentTile()
        assertEquals(TacticalTargetType.TradeUnitLand, ctx.targets.all.first { it.tile == trader }.type)
        val plots = ctx.targets.all.filter { it.type == TacticalTargetType.TradeUnitLandPlot }.map { it.tile }
        assertTrue(route.getTiles().filter { it != trader && !it.isCityCenter() }.all { it in plots })

        val friend = game.addCiv()
        friend.diplomacyFunctions.makeCivilizationsMeet(victim)
        friend.getDiplomacyManager(victim)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(friend, true)
        val peaceful = context(friend).targets.all
        assertTrue(peaceful.none { it.type in TRADE_TYPES })
    }

    @Test
    fun `a trader at sea is a sea target`() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.coast)
        game.setTileTerrain(tile(0).position, Constants.desert)
        game.setTileTerrain(tile(8).position, Constants.desert)
        val route = route(unit = "Cargo Ship")
        assertTrue(route.isSea)
        val ctx = context()
        assertEquals(TacticalTargetType.TradeUnitSea, ctx.targets.all.first { it.tile == route.getCurrentTile() }.type)
        assertTrue(ctx.targets.all.any { it.type == TacticalTargetType.TradeUnitSeaPlot })
    }

    @Test
    fun `a road of a trade route in enemy land is a pillage target`() {
        val route = route()
        val road = route.getTiles()[1]
        road.setOwningCity(victim.cities.first())
        road.roadStatus = RoadStatus.Road
        assertEquals(TacticalTargetType.Improvement, context().targets.all.first { it.tile == road }.type)
        val plain = tile(-3)
        plain.setOwningCity(victim.cities.first())
        plain.roadStatus = RoadStatus.Road
        assertTrue("a road off the routes is not", context().targets.all.none { it.tile == plain })
    }
    // endregion

    // region Plundering

    @Test
    fun `a unit that can reach the trader plunders it, takes the gold and is done for the turn`() {
        val route = route()
        val trader = route.getCurrentTile()
        val unit = game.addUnit("Horseman", civ, besideTrader(route))
        val ctx = context()
        TradeMoves.plotPlunderTradeUnitMoves(ctx)
        assertTrue(victim.tradeRoutes.routes.isEmpty())
        assertEquals(100, civ.gold)
        assertEquals(trader, unit.getTile())
        assertTrue(unit in ctx.cache.processedUnits)
        assertEquals(0f, unit.currentMovement)
    }

    @Test
    fun `a unit that cannot reach the trader this turn leaves it alone`() {
        val route = route()
        val unit = game.addUnit("Warrior", civ, tile(-7))
        TradeMoves.plotPlunderTradeUnitMoves(context())
        assertEquals(1, victim.tradeRoutes.routes.size)
        assertEquals(0, civ.gold)
        assertNotNull(unit.getTile())
    }

    @Test
    fun `the tactical turn plunders a trader in reach`() {
        // far from both cities: a unit next to the trader has nothing else to do
        val route = route(distance = 10).also { it.pathIndex = 5 }
        game.addUnit("Warrior", civ, besideTrader(route))
        see()
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), AutomationCache())
        assertTrue(victim.tradeRoutes.routes.isEmpty())
        assertEquals(100, civ.gold)
    }

    @Test
    fun `only a ship goes for a trader at sea and a route by sea pays double`() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.coast)
        game.setTileTerrain(tile(0).position, Constants.desert)
        game.setTileTerrain(tile(8).position, Constants.desert)
        val route = route(unit = "Cargo Ship")
        val ship = game.addUnit("Trireme", civ, route.getCurrentTile().neighbors.first { it !in route.getTiles() })
        TradeMoves.plotPlunderTradeUnitMoves(context())
        assertTrue(victim.tradeRoutes.routes.isEmpty())
        assertEquals(200, civ.gold)
        assertTrue(ship.currentMovement == 0f)
    }

    @Test
    fun `barbarians plunder the trader they can reach`() {
        val barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
        val route = route()
        game.addUnit("Warrior", barbarians, besideTrader(route))
        TradeMoves.plotPlunderTradeUnitMoves(context(barbarians))
        assertTrue(victim.tradeRoutes.routes.isEmpty())
        assertEquals(100, barbarians.gold)
    }
    // endregion

    // region Parking and ganking

    @Test
    fun `a free unit goes to the nearest route tile and each tile gets one unit`() {
        val route = route(distance = 2)
        val first = game.addUnit("Warrior", civ, tile(-6))
        val second = game.addUnit("Warrior", civ, tile(-7))
        val ctx = context()
        TradeMoves.plotParkOnTradeRouteMoves(ctx)
        assertTrue(first in ctx.cache.processedUnits)
        assertTrue("the route has one tile between its cities", first.getTile().aerialDistanceTo(route.getTiles()[1]) < 7)
        assertFalse("no other tile to park on", second in ctx.cache.processedUnits)
    }

    @Test
    fun `a barbarian looks for the tile of most routes within its movement, none if no route is near`() {
        val barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
        val route = route(distance = 10)
        val unit = game.addUnit("Warrior", barbarians, tile(5))
        val ctx = context(barbarians)
        val found = BarbarianMoves.findGankTradeRouteTarget(ctx, unit)
        assertNotNull(found)
        assertTrue(found in route.getTiles())
        assertTrue(found != unit.getTile())
        assertEquals("no hunted target within range 5 of the middle", found, BarbarianMoves.findBestLandMove(ctx, unit))

        val far = game.addUnit("Warrior", barbarians, game.tileMap[6, -6])
        assertNull(BarbarianMoves.findGankTradeRouteTarget(context(barbarians), far))
    }
    // endregion

    private companion object {
        val TRADE_TYPES = setOf(TacticalTargetType.TradeUnitLand, TacticalTargetType.TradeUnitSea,
            TacticalTargetType.TradeUnitLandPlot, TacticalTargetType.TradeUnitSeaPlot)
    }
}
