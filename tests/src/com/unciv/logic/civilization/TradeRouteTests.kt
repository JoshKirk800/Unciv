package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.managers.TradeRouteManager
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.city.City
import com.unciv.models.TradeRoute
import com.unciv.models.UnitActionType
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TradeRouteTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var caravan: BaseUnit
    private lateinit var cargoShip: BaseUnit

    private val landUnique = "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn"
    private val seaUnique = "Can establish trade routes with a base range of [20] tiles, travelling [4] tiles per turn"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(16)
        civA = game.addCiv("[+4] Trade Route slots")
        civB = game.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.makePeace()
        caravan = game.createBaseUnit("Civilian", landUnique)
        cargoShip = game.createBaseUnit("Civilian Water", seaUnique)
        for (tile in game.tileMap.values) {
            tile.setExplored(civA, true)
            tile.setExplored(civB, true)
        }
    }

    private fun cityAt(civ: Civilization, y: Int) = game.addCity(civ, game.tileMap[0, y])

    /** Cities of both civs on the line x = 0, [distance] tiles apart */
    private fun twoCities(distance: Int): Pair<City, City> = cityAt(civA, 0) to cityAt(civB, distance)

    private fun establish(origin: City, destination: City, unit: BaseUnit = caravan, kind: TradeRouteKind = TradeRouteKind.International): TradeRoute? {
        val mapUnit = game.addUnit(unit.name, origin.civ, origin.getCenterTile())
        val route = origin.civ.tradeRoutes.establish(mapUnit, destination, kind)
        if (route == null) mapUnit.destroy() // as a player would leave it waiting
        return route
    }

    private fun waitingUnits(civ: Civilization, unit: BaseUnit = caravan) = civ.units.getCivUnits().count { it.name == unit.name }

    @Test
    fun `a land route reaches a destination 10 tiles away`() {
        val (origin, destination) = twoCities(10)
        // 10 flat steps cost 1010 of the 1099 allowed
        assertTrue(civA.tradeRoutes.getDestinations(origin, caravan).any { it.city == destination })
    }

    @Test
    fun `a land route does not reach a destination 11 tiles away`() {
        val (origin, destination) = twoCities(11)
        // 11 flat steps cost 1111
        assertTrue(civA.tradeRoutes.getDestinations(origin, caravan).none { it.city == destination })
    }

    @Test
    fun `roads make the range reach further`() {
        val origin = cityAt(civA, 0)
        val far = cityAt(civB, 14)
        assertTrue(civA.tradeRoutes.getDestinations(origin, caravan).none { it.city == far })
        for (y in 0..14) game.tileMap[0, y].roadStatus = RoadStatus.Road
        assertTrue(civA.tradeRoutes.getDestinations(origin, caravan).any { it.city == far })
    }

    @Test
    fun `a range bonus in percent applies to the base range of the origin city only`() {
        val origin = cityAt(civA, 0)
        val far = cityAt(civB, 14)
        val bonus = game.createBuilding("[+50]% range for [Land] trade routes <in this city>")
        origin.cityConstructions.addBuilding(bonus)
        assertTrue(civA.tradeRoutes.getDestinations(origin, caravan).any { it.city == far })
    }

    @Test
    fun `routes to the destination are refused at war, when not met, to oneself and twice`() {
        val (origin, destination) = twoCities(6)
        val own = cityAt(civA, 3)
        val stranger = game.addCiv()
        val strangerCity = cityAt(stranger, 8)
        val destinations = { civA.tradeRoutes.getDestinations(origin, caravan).map { it.city } }
        assertFalse("not met", strangerCity in destinations())
        assertFalse("own city without a granary", own in destinations())
        assertTrue(destination in destinations())

        assertNotNull(establish(origin, destination))
        assertFalse("twice", destination in destinations())

        civA.diplomacyFunctions.makeCivilizationsMeet(stranger)
        civA.getDiplomacyManager(stranger)!!.makePeace()
        assertTrue(strangerCity in destinations())
        civA.getDiplomacyManager(stranger)!!.declareWar()
        assertFalse("at war", strangerCity in destinations())
    }

    @Test
    fun `a route uses up the unit, lasts the circuits of the original, and the unit comes back`() {
        val (origin, destination) = twoCities(7)
        val route = establish(origin, destination)!!
        assertEquals("the unit is used up", 0, waitingUnits(civA))
        assertEquals(8, route.path.size)
        // 7 steps there and back at 2 per turn is 7 turns, so 4 circuits of 28 turns come closest to 30
        assertEquals(4, route.circuitsToComplete)
        assertEquals(game.gameInfo.turns + 28, route.turnRouteComplete)

        // 2*7*4 = 56 steps, two were made at the start
        repeat(26) { civA.tradeRoutes.startTurn() }
        assertTrue(route in civA.tradeRoutes.routes)
        assertEquals(0, waitingUnits(civA))
        civA.tradeRoutes.startTurn()
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals("a new unit waits in the city", 1, waitingUnits(civA))
        assertEquals(origin.location, civA.units.getCivUnits().first { it.name == caravan.name }.getTile().position)
    }

    @Test
    fun `the trader walks to the destination and back`() {
        val (origin, destination) = twoCities(5)
        val route = establish(origin, destination)!!
        val visited = arrayListOf(route.pathIndex)
        repeat(8) { civA.tradeRoutes.startTurn(); visited.add(route.pathIndex) }
        // 5 steps to the destination, 2 per turn: it turns around there, and again at the origin where a round trip is complete
        assertEquals(listOf(2, 4, 4, 2, 0, 2, 4, 4, 2), visited)
        assertEquals(1, route.circuitsCompleted)
    }

    @Test
    fun `gold of the origin and of the destination, with the bonuses of the buildings`() {
        val (origin, destination) = twoCities(5)
        val base = establish(origin, destination)!!
        assertTrue("at least one gold for the origin", base.originYields.gold >= 1f)
        assertEquals("one gold for the destination", 1f, base.destinationYields.gold, 0.001f)
        civA.tradeRoutes.routes.clear()
        civA.tradeRoutes.updateYields()
        val baseGold = base.originYields.gold

        origin.cityConstructions.addBuilding(game.createBuilding("[+2] Gold on [Land] trade routes <in this city>"))
        destination.cityConstructions.addBuilding(game.createBuilding("[+1] Gold on incoming [International] trade routes for their origin <in this city>"))
        destination.cityConstructions.addBuilding(game.createBuilding("[+1] Gold from incoming [International] trade routes <in this city>"))
        val route = establish(origin, destination)!!
        assertEquals(baseGold + 3f, route.originYields.gold, 0.02f)
        assertEquals(2f, route.destinationYields.gold, 0.001f)
    }

    @Test
    fun `a bonus for a kind of route only applies to that kind`() {
        val (origin, destination) = twoCities(5)
        origin.cityConstructions.addBuilding(game.createBuilding("[+2] Gold on [Water] trade routes <in this city>"))
        val routeWithoutBonus = establish(origin, destination)!!
        val gold = routeWithoutBonus.originYields.gold
        civA.tradeRoutes.routes.clear()
        origin.cityConstructions.addBuilding(game.createBuilding("[+2] Gold on [Land] trade routes <in this city>"))
        val route = establish(origin, destination)!!
        assertEquals(gold + 2f, route.originYields.gold, 0.02f)
    }

    @Test
    fun `a route by sea doubles the gold, and needs coasts`() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.coast)
        game.setTileTerrain(game.tileMap[0, 0].position, Constants.desert)
        game.setTileTerrain(game.tileMap[0, 8].position, Constants.desert)
        val origin = cityAt(civA, 0)
        val destination = cityAt(civB, 8)
        assertTrue(civA.tradeRoutes.getDestinations(origin, cargoShip).any { it.city == destination })
        assertTrue("a land unit cannot cross the water", civA.tradeRoutes.getDestinations(origin, caravan).none { it.city == destination })

        val first = establish(origin, destination, cargoShip)!!
        assertTrue(first.isSea)
        val gold = first.originYields.gold
        civA.tradeRoutes.routes.clear()
        origin.cityConstructions.addBuilding(game.createBuilding("[+1] Gold on [Water] trade routes <in this city>"))
        val second = establish(origin, destination, cargoShip)!!
        // the bonus is part of what the sea bonus of 100% doubles
        assertEquals(gold + 2f, second.originYields.gold, 0.02f)
        assertEquals("the destination gets double of its base too", 2f, second.destinationYields.gold, 0.001f)
    }

    @Test
    fun `the yields of a route are added to the city after its percent bonuses`() {
        val (origin, destination) = twoCities(5)
        val route = establish(origin, destination)!!
        origin.cityConstructions.addBuilding(game.createBuilding("[+100]% Gold"))
        origin.cityStats.update()
        val entry = origin.cityStats.finalStatList[TradeRouteManager.STAT_SOURCE]!!
        assertEquals(route.originYields.gold, entry.gold, 0.001f)
        assertEquals(1f, destination.cityStats.finalStatList[TradeRouteManager.STAT_SOURCE]!!.gold, 0.001f)
    }

    @Test
    fun `science comes from the techs the other knows and we lack, half of them rounded up`() {
        val (origin, destination) = twoCities(5)
        val techs = game.ruleset.technologies.keys.toList()
        for (tech in techs.take(5)) civB.tech.addTechnology(tech)
        val route = establish(origin, destination)!!
        val known = civB.tech.techsResearched.count { it !in civA.tech.techsResearched }
        assertTrue(known > 0)
        assertEquals(((known + 1) / 2).toFloat(), route.originYields.science, 0.001f)
        assertEquals("they do not know more of ours", 0f, route.destinationYields.science, 0.001f)
    }

    @Test
    fun `an enemy unit where the trader walks plunders the route`() {
        val (origin, destination) = twoCities(8)
        val enemy = game.addCiv()
        enemy.diplomacyFunctions.makeCivilizationsMeet(civA)
        enemy.getDiplomacyManager(civA)!!.declareWar()
        val route = establish(origin, destination)!!
        // the trader is at index 2 and goes on to 4
        game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(route.path[3]))
        val goldBefore = enemy.gold
        civA.tradeRoutes.startTurn()
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals(100, enemy.gold - goldBefore)
        assertEquals("no unit comes back", 0, waitingUnits(civA))
    }

    @Test
    fun `a military unit can plunder the trader beneath it without waiting for the route to move`() {
        val (origin, destination) = twoCities(8)
        val enemy = game.addCiv()
        enemy.diplomacyFunctions.makeCivilizationsMeet(civA)
        enemy.getDiplomacyManager(civA)!!.declareWar()
        val route = establish(origin, destination)!!
        val attacker = game.addDefaultMeleeUnitWithUniques(enemy, game.getTile(route.path[3]))
        route.pathIndex = 3
        val moves = attacker.currentMovement
        val action = UnitActions.getUnitActions(attacker, UnitActionType.Pillage)
            .single { it.title == "Plunder trade route" }

        action.action!!.invoke()

        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals(100, enemy.gold)
        assertEquals("trade plunder does not consume the unit's movement", moves, attacker.currentMovement)
    }

    @Test
    fun `barbarians get a bonus unit with no moves after plundering a route`() {
        val (origin, destination) = twoCities(8)
        val barbarians = game.addBarbarianCiv()
        game.gameInfo.barbarians.setTransients(game.gameInfo)
        val route = establish(origin, destination)!!
        val plunderer = game.addUnit("Brute", barbarians, game.getTile(route.path[3]))
        val previousUnits = barbarians.units.getCivUnits().toSet()

        civA.tradeRoutes.startTurn()

        assertTrue("the route was plundered", civA.tradeRoutes.routes.isEmpty())
        assertTrue(plunderer in barbarians.units.getCivUnits())
        val newUnits = barbarians.units.getCivUnits().filterNot { it in previousUnits }.toList()
        assertEquals("one bonus barbarian despite the early turn", 1, newUnits.size)
        assertEquals(0f, newUnits.single().currentMovement)
    }

    @Test
    fun `plundering a route by sea gives twice the gold`() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.coast)
        game.setTileTerrain(game.tileMap[0, 0].position, Constants.desert)
        game.setTileTerrain(game.tileMap[0, 8].position, Constants.desert)
        val origin = cityAt(civA, 0)
        val destination = cityAt(civB, 8)
        val route = establish(origin, destination, cargoShip)!!
        val enemy = game.addCiv()
        enemy.diplomacyFunctions.makeCivilizationsMeet(civA)
        enemy.getDiplomacyManager(civA)!!.declareWar()
        val ship = game.createBaseUnit("Melee Water").also { it.strength = 10; it.movement = 4 }
        game.addUnit(ship.name, enemy, game.getTile(route.path[route.pathIndex]))
        val goldBefore = enemy.gold
        civA.tradeRoutes.getPlunderableRoutes(game.getTile(route.path[route.pathIndex]).militaryUnit!!).single()
            .also { civA.tradeRoutes.plunder(it, game.getTile(route.path[route.pathIndex]).militaryUnit!!) }
        assertEquals(200, enemy.gold - goldBefore)
    }

    @Test
    fun `peace does not plunder`() {
        val (origin, destination) = twoCities(8)
        val friend = game.addCiv()
        friend.diplomacyFunctions.makeCivilizationsMeet(civA)
        friend.getDiplomacyManager(civA)!!.makePeace()
        val route = establish(origin, destination)!!
        game.addDefaultMeleeUnitWithUniques(friend, game.getTile(route.path[3]))
        civA.tradeRoutes.startTurn()
        assertEquals(1, civA.tradeRoutes.routes.size)
    }

    @Test
    fun `war ends the routes between the two, nobody gets a unit back`() {
        val (origin, destination) = twoCities(6)
        establish(origin, destination)
        civB.getDiplomacyManager(civA)!!.declareWar()
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals(0, waitingUnits(civA))
    }

    @Test
    fun `losing the destination gives the unit back, losing the origin does not`() {
        val (origin, destination) = twoCities(6)
        establish(origin, destination)
        destination.moveToCiv(game.addCiv())
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals(1, waitingUnits(civA))

        val otherOrigin = cityAt(civA, 12)
        val newDestination = cityAt(civB, 15)
        // the second route runs between the two cities that are left
        establish(otherOrigin, newDestination)
        val unitsBefore = waitingUnits(civA)
        otherOrigin.moveToCiv(civB)
        assertTrue(civA.tradeRoutes.routes.isEmpty())
        assertEquals(unitsBefore, waitingUnits(civA))
    }

    @Test
    fun `slots are the sum of the bonuses made larger by the percentages, rounded down`() {
        val rich = game.addCiv("[+3] Trade Route slots", "[+50]% Trade Route slots")
        assertEquals(4, rich.tradeRoutes.getNumSlots())
        val none = game.addCiv()
        assertEquals(0, none.tradeRoutes.getNumSlots())
    }

    @Test
    fun `a trade unit cannot be built without a free slot or a place to go`() {
        val single = game.addCiv("[+1] Trade Route slots")
        val ownCity = game.addCity(single, game.tileMap[0, -3])
        game.addCity(civB, game.tileMap[0, -6])
        single.diplomacyFunctions.makeCivilizationsMeet(civB)
        single.getDiplomacyManager(civB)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(single, true)
        val reasons = { caravan.getRejectionReasons(ownCity.cityConstructions).map { it.type } }
        assertFalse(RejectionReasonType.NoTradeRouteSlot in reasons())

        val route = establish(ownCity, civB.cities.first())
        assertNotNull(route)
        assertTrue("the slot is used by the route", RejectionReasonType.NoTradeRouteSlot in reasons())

        val noWhere = game.addCiv("[+1] Trade Route slots")
        val lonely = game.addCity(noWhere, game.tileMap[0, 15])
        assertTrue("nothing in reach", RejectionReasonType.NoTradeRouteSlot in caravan.getRejectionReasons(lonely.cityConstructions).map { it.type })
    }

    @Test
    fun `a route between own cities brings food to the destination, and only from a city that has the building`() {
        val origin = cityAt(civA, 0)
        val destination = cityAt(civA, 4)
        assertNull(establish(origin, destination, kind = TradeRouteKind.Food))
        origin.cityConstructions.addBuilding(game.createBuilding("Allows [Food] trade routes between your cities"))
        assertNull("a granary does not allow production", establish(origin, destination, kind = TradeRouteKind.Production))
        val route = establish(origin, destination, kind = TradeRouteKind.Food)!!
        assertEquals(3f, route.destinationYields.food, 0.001f)
        assertEquals(0f, route.originYields.food, 0.001f)
        assertEquals(0f, route.destinationYields.gold, 0.001f)
    }

    @Test
    fun `a percentage makes own routes bring more`() {
        val rich = game.addCiv("[+50]% yields from trade routes within your civilization")
        val origin = game.addCity(rich, game.tileMap[0, -5])
        val destination = game.addCity(rich, game.tileMap[0, -9])
        for (tile in game.tileMap.values) tile.setExplored(rich, true)
        origin.cityConstructions.addBuilding(game.createBuilding("Allows [Production] trade routes between your cities"))
        val route = establish(origin, destination, kind = TradeRouteKind.Production)!!
        assertEquals(4.5f, route.destinationYields.production, 0.001f)
    }

    @Test
    fun `routes survive cloning`() {
        val (origin, destination) = twoCities(6)
        val route = establish(origin, destination)!!
        val copy = civA.tradeRoutes.clone()
        copy.setTransients(civA)
        val clonedRoute = copy.routes.single()
        assertEquals(route.path, clonedRoute.path)
        assertEquals(route.originYields.gold, clonedRoute.originYields.gold, 0.0001f)
        assertEquals(route.turnRouteComplete, clonedRoute.turnRouteComplete)
    }

    @Test
    fun `routes survive saving and loading`() {
        val (origin, destination) = twoCities(6)
        val route = establish(origin, destination)!!
        civA.tradeRoutes.startTurn() // the trader has moved on and turned, so that the saved state is not a fresh one
        val text = com.unciv.json.json().toJson(civA.tradeRoutes)
        val loaded = com.unciv.json.json().fromJson(TradeRouteManager::class.java, text)
        loaded.setTransients(civA)
        val loadedRoute = loaded.routes.single()
        assertEquals(route.path, loadedRoute.path)
        assertEquals(route.pathIndex, loadedRoute.pathIndex)
        assertEquals(route.movingForward, loadedRoute.movingForward)
        assertEquals(route.circuitsToComplete, loadedRoute.circuitsToComplete)
        assertEquals(route.tilesPerTurn, loadedRoute.tilesPerTurn)
        assertEquals(route.unitName, loadedRoute.unitName)
        assertEquals(route.kind, loadedRoute.kind)
        assertEquals(route.originYields.gold, loadedRoute.originYields.gold, 0.0001f)
        assertEquals(origin.location, loadedRoute.getOriginCity()!!.location)
    }

    @Test
    fun `the number of routes and of partners can be counted`() {
        val (origin, destination) = twoCities(6)
        val second = cityAt(civB, 3)
        establish(origin, destination)
        establish(origin, second)
        val context = com.unciv.models.ruleset.unique.GameContext(civA)
        assertEquals(2, com.unciv.models.ruleset.unique.Countables.TradeRoutes.eval("Trade Routes", context))
        assertEquals(2, com.unciv.models.ruleset.unique.Countables.FilteredTradeRoutes.eval("[Land] Trade Routes", context))
        assertEquals(0, com.unciv.models.ruleset.unique.Countables.FilteredTradeRoutes.eval("[Water] Trade Routes", context))
        assertEquals(1, com.unciv.models.ruleset.unique.Countables.TradeRoutePartners.eval("Trade Route Partners", context))
        assertEquals("seen from the other end", 1, com.unciv.models.ruleset.unique.Countables.TradeRoutePartners.eval("Trade Route Partners", com.unciv.models.ruleset.unique.GameContext(civB)))
    }
}
