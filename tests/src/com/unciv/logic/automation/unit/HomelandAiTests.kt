package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.ExplorationAi
import com.unciv.logic.automation.strategy.ExplorationPlots
import com.unciv.logic.automation.strategy.ReconState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvHomelandAI's military moves against the DLL formulas (Brave New World data): the priority list with flavors, which unit garrisons
 *  which city, the weight of sentry points, the explorers' plot score and goody assignments, and the movement part of upgrades.
 */
@RunWith(GdxTestRunner::class)
class HomelandAiTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16, Constants.grassland)
        civ = neutralCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun neutralCiv(): Civilization {
        val created = game.addCiv()
        for (flavor in Flavor.entries) created.ai.setPersonalityFlavor(flavor, 0)
        return created
    }

    /** The tile `y` steps from the middle along a straight line: distances are |a - b| */
    private fun tile(y: Int): Tile = game.tileMap[0, y]

    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun run(vararg units: MapUnit, cache: AutomationCache = AutomationCache()): AutomationCache {
        see()
        HomelandAi.doTurn(civ, units.toList(), cache)
        return cache
    }

    /** A policy that encourages garrisons puts the garrison move above the sentry move, so the tests of garrisons need no sentry points to be rid of */
    private fun encourageGarrisons() {
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Oligarchy"]!!)
    }

    private fun distance(unit: MapUnit, tile: Tile) = unit.getTile().aerialDistanceTo(tile)

    // region Priorities

    @Test
    fun `without flavors the moves run in the order of the base priorities, equal ones in the original's enum order`() {
        val order = HomelandAi.priorities(civ).map { it.move to it.priority }
        assertEquals(listOf(
            HomelandMove.AncientRuins to 40, HomelandMove.GarrisonCityState to 40,
            HomelandMove.Explore to 35, HomelandMove.ExploreSea to 35,
            HomelandMove.Heal to 30, HomelandMove.ToSafety to 30,
            HomelandMove.Upgrade to 25, HomelandMove.Sentry to 20, HomelandMove.MobileReserve to 15,
            HomelandMove.Garrison to 10, HomelandMove.Patrol to 0), order)
    }

    @Test
    fun `flavors are added to the priorities, defense and recon scaled by 0_3 and truncated`() {
        civ.ai.setPersonalityFlavor(Flavor.Defense, 7)   // 2.1 -> 2: garrison, heal, to safety, reserve, sentry
        civ.ai.setPersonalityFlavor(Flavor.Recon, 20)    // 6: explore and goody huts
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 9) // 9, not scaled
        civ.ai.setPersonalityFlavor(Flavor.Offense, 20)  // read by the original but added to no move
        val priorities = HomelandAi.priorities(civ).associate { it.move to it.priority }
        assertEquals(12, priorities[HomelandMove.Garrison])
        assertEquals(32, priorities[HomelandMove.Heal])
        assertEquals(32, priorities[HomelandMove.ToSafety])
        assertEquals(17, priorities[HomelandMove.MobileReserve])
        assertEquals(22, priorities[HomelandMove.Sentry])
        assertEquals(41, priorities[HomelandMove.Explore])
        assertEquals(46, priorities[HomelandMove.AncientRuins])
        assertEquals(34, priorities[HomelandMove.Upgrade])
        assertEquals(0, priorities[HomelandMove.Patrol])
        assertEquals(HomelandMove.AncientRuins, HomelandAi.priorities(civ).first().move)
    }

    @Test
    fun `a policy that encourages garrisons puts the garrison one above the sentry`() {
        assertTrue(HomelandAi.priorities(civ).indexOfFirst { it.move == HomelandMove.Garrison } >
            HomelandAi.priorities(civ).indexOfFirst { it.move == HomelandMove.Sentry })
        encourageGarrisons()
        val priorities = HomelandAi.priorities(civ).associate { it.move to it.priority }
        assertEquals(priorities[HomelandMove.Sentry]!! + 1, priorities[HomelandMove.Garrison])
    }

    // endregion
    // region Garrison

    @Test
    fun `the more threatened city is garrisoned first`() {
        val calm = game.addCity(civ, tile(0))
        val threatened = game.addCity(civ, tile(10))
        game.addUnit("Warrior", enemy, tile(12))
        val unit = game.addUnit("Archer", civ, tile(5))
        encourageGarrisons()
        see()
        assertTrue(civ.ai.plotDanger.cityThreat(threatened) > civ.ai.plotDanger.cityThreat(calm))
        assertEquals(listOf(threatened, calm), HomelandAi.findTargets(civ, AutomationCache()).cities.map { it.city })
        run(unit)
        assertTrue("it set out for the threatened city", distance(unit, threatened.getCenterTile()) < 5)
        assertTrue(unit.isMoving())
    }

    @Test
    fun `one unit garrisons a city per turn and a unit already inside fortifies there`() {
        val city = game.addCity(civ, tile(0))
        val inside = game.addUnit("Archer", civ, tile(0))
        val other = game.addUnit("Archer", civ, tile(3))
        encourageGarrisons()
        val cache = run(inside, other)
        assertEquals(tile(0), inside.getTile())
        assertTrue(inside.isFortified())
        assertTrue(inside in cache.processedUnits)
        assertEquals(game.gameInfo.turns, civ.ai.tactical.lastTurnGarrisonAssigned(city))
        assertNotEquals("the second unit is not sent to a city with a garrison", city.getCenterTile(), other.getTile())
        assertFalse(other.isMoving())
    }

    @Test
    fun `a ranged unit garrisons before a melee unit when it is not farther away`() {
        val city = game.addCity(civ, tile(0))
        val melee = game.addUnit("Warrior", civ, tile(-2))
        val ranged = game.addUnit("Archer", civ, tile(2))
        encourageGarrisons()
        run(melee, ranged)
        assertEquals(city.getCenterTile(), ranged.getTile())
        assertNotEquals("the warrior was left to other moves", city.getCenterTile(), melee.getTile())
    }

    @Test
    fun `a closer melee unit garrisons before a ranged unit that needs longer`() {
        val city = game.addCity(civ, tile(0))
        val melee = game.addUnit("Warrior", civ, tile(1))
        val ranged = game.addUnit("Archer", civ, tile(6))
        encourageGarrisons()
        run(melee, ranged)
        assertEquals(city.getCenterTile(), melee.getTile())
        assertNotEquals(city.getCenterTile(), ranged.getTile())
    }

    @Test
    fun `a unit with promotion strength bonuses is not used to garrison`() {
        val city = game.addCity(civ, tile(0))
        val promoted = game.addUnit("Warrior", civ, tile(1))
        promoted.promotions.addPromotion("Survivalism I", true)   // +25% Strength when defending
        val plain = game.addUnit("Warrior", civ, tile(4))
        encourageGarrisons()
        run(promoted, plain)
        assertNotEquals(city.getCenterTile(), promoted.getTile())
        assertTrue("the plain unit, farther away, set out", distance(plain, city.getCenterTile()) < 4)
    }

    @Test
    fun `a unit that cannot reach the city is passed over for one that can`() {
        val city = game.addCity(civ, tile(0))
        val stranded = game.addUnit("Warrior", civ, tile(-3)) // the nearest, but walled in by water
        for (neighbor in tile(-3).neighbors) game.setTileTerrain(neighbor.position, Constants.ocean)
        val reachable = game.addUnit("Warrior", civ, tile(7))
        encourageGarrisons()
        run(stranded, reachable)
        assertEquals(tile(-3), stranded.getTile())
        assertTrue(distance(reachable, city.getCenterTile()) < 7)
        assertTrue(reachable.isMoving())
    }

    @Test
    fun `a city the tactical AI garrisoned this turn gets no unit`() {
        val guarded = game.addCity(civ, tile(0))
        val open = game.addCity(civ, tile(10))
        val defender = game.addUnit("Warrior", civ, tile(0))
        val unit = game.addUnit("Archer", civ, tile(5))
        encourageGarrisons()
        val cache = AutomationCache().also { it.processedUnits.add(defender) }
        see()
        assertEquals("a city with a defender the tactical AI commands is no target", listOf(open), HomelandAi.findTargets(civ, cache).cities.map { it.city })
        civ.ai.tactical.setLastTurnGarrisonAssigned(open, game.gameInfo.turns)
        run(unit, cache = cache)
        assertFalse("no city was left to garrison", unit.isMoving())
        assertNotEquals(guarded.getCenterTile(), unit.getTile())
        assertNotEquals(open.getCenterTile(), unit.getTile())
    }

    @Test
    fun `a city-state garrisons by its own move`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val city = game.addCity(cityState, tile(0))
        val unit = game.addUnit("Warrior", cityState, tile(2))
        for (tile in game.tileMap.values) tile.setExplored(cityState, true)
        cityState.viewableTiles = game.tileMap.values.toSet()
        cityState.ai.invalidateTurnCaches()
        HomelandAi.doTurn(cityState, listOf(unit), AutomationCache())
        assertEquals(city.getCenterTile(), unit.getTile())
        assertTrue(unit.isFortified())
    }

    // endregion
    // region Sentry points

    @Test
    fun `a sentry point weighs the plots outside, the defense modifier and the danger, doubled on hills and halved on the coast`() {
        see()
        val flat = tile(-9)
        val defense = civ.ai.tactical.map.defenseModifier(flat)
        assertEquals(3 * 100 + defense, HomelandAi.sentryWeight(civ, flat, 3))
        val hills = game.setTileTerrain(HexCoord(0, -10), Constants.hill)
        see()
        assertEquals((2 * 100 + civ.ai.tactical.map.defenseModifier(hills)) * 2, HomelandAi.sentryWeight(civ, hills, 2))
        val coast = tile(-11)
        for (neighbor in coast.neighbors.take(1)) game.setTileTerrain(neighbor.position, "Coast")
        see()
        assertEquals((100 + civ.ai.tactical.map.defenseModifier(coast)) / 2, HomelandAi.sentryWeight(civ, coast, 1))
    }

    @Test
    fun `a sentry point near a threatened city adds its threat times population over 50, the capital's weight more`() {
        val city = game.addCity(civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        see()
        val threat = civ.ai.plotDanger.cityThreat(city)
        assertTrue(threat > 0)
        val point = tile(3)
        val expected = ((100 + civ.ai.tactical.map.defenseModifier(point) + civ.ai.plotDanger.get(point)) +
            threat * city.population.population / 50) * 125 / 100
        assertEquals(expected, HomelandAi.sentryWeight(civ, point, 1))
        // beyond 5 tiles of the city its threat counts for nothing
        val far = tile(7)
        assertEquals(100 + civ.ai.tactical.map.defenseModifier(far) + civ.ai.plotDanger.get(far), HomelandAi.sentryWeight(civ, far, 1))
    }

    @Test
    fun `sentry points are sorted by weight and no two of them are adjacent`() {
        game.addCity(civ, tile(0))
        game.addUnit("Warrior", enemy, tile(6))
        see()
        val points = HomelandAi.findTargets(civ, AutomationCache()).sentryPoints
        assertTrue(points.size > 1)
        assertEquals(points.sortedByDescending { it.weight }.map { it.tile }, points.map { it.tile })
        for (a in points) for (b in points) if (a !== b) assertNotEquals(1, a.tile.aerialDistanceTo(b.tile))
        // the point a unit stands on (ours) is allowed, one with an enemy on it is not
        assertTrue(points.none { it.tile == tile(6) })
    }

    @Test
    fun `the unit that is sent to a sentry point fortifies there when it arrives`() {
        game.addCity(civ, tile(0))
        see()
        val point = HomelandAi.findTargets(civ, AutomationCache()).sentryPoints.first().tile
        val unit = game.addUnit("Warrior", civ, point.neighbors.first { it.getOwner() == civ })
        val cache = run(unit)
        assertEquals(point, unit.getTile())
        assertTrue(unit.isFortified())
        assertTrue(unit in cache.processedUnits)
    }

    @Test
    fun `a road tile is a mobile reserve point only where the sentry rule did not take it`() {
        game.addCity(civ, tile(0))
        val withWorker = tile(1)
        game.addUnit("Worker", civ, withWorker)
        withWorker.roadStatus = com.unciv.logic.map.tile.RoadStatus.Road
        val empty = tile(-1)
        empty.roadStatus = com.unciv.logic.map.tile.RoadStatus.Road
        see()
        val roads = HomelandAi.findTargets(civ, AutomationCache()).roads.map { it.tile }
        assertEquals(listOf(withWorker), roads)
    }

    // endregion
    // region Exploring

    private fun exploreOnlyAround(center: Tile, radius: Int) {
        for (tile in game.tileMap.values) tile.setExplored(civ, tile.aerialDistanceTo(center) <= radius)
    }

    @Test
    fun `a land plot scores the unexplored plots around it by what they are`() {
        exploreOnlyAround(tile(0), 3)
        val edge = tile(3)
        val unexplored = edge.neighbors.filter { !civ.hasExplored(it) }.toList()
        assertEquals(3, unexplored.size)
        assertEquals(3 * 100, ExplorationAi.scoreExplorePlot(civ, edge, 1, forShip = false))
        game.setTileTerrain(unexplored[0].position, Constants.hill)
        assertEquals(200 + 2 * 100, ExplorationAi.scoreExplorePlot(civ, edge, 1, forShip = false))
        game.setTileTerrain(unexplored[1].position, Constants.mountain)
        assertEquals(200 + 10 + 100, ExplorationAi.scoreExplorePlot(civ, edge, 1, forShip = false))
        game.setTileTerrain(unexplored[2].position, Constants.ocean)
        assertEquals(200 + 10 + 10, ExplorationAi.scoreExplorePlot(civ, edge, 1, forShip = false))
        // a ship values open land the most, hills and mountains less and water least
        assertEquals(100 + 100 + 10, ExplorationAi.scoreExplorePlot(civ, edge, 1, forShip = true))
        assertEquals(0, ExplorationAi.scoreExplorePlot(civ, tile(0), 1, forShip = false))
    }

    @Test
    fun `an unexplored plot beside no revealed one counts 100, with a bonus for being near`() {
        exploreOnlyAround(tile(0), 0)
        // Range 2 around the one revealed plot: its six neighbors (next to it, so by what they are: 100 each) + (2 - 1) near bonus, and the
        // twelve plots at distance 2, which no revealed plot touches: 100 each + 0
        assertEquals(6 * (100 + 1) + 12 * 100, ExplorationAi.scoreExplorePlot(civ, tile(0), 2, forShip = false))
    }

    @Test
    fun `with fewer explorers than huts each explorer takes the hut it reaches first, with more each hut the explorer who arrives first`() {
        val near = tile(4)
        val far = tile(12)
        for (hut in listOf(near, far)) hut.improvement = "Ancient ruins"
        val scoutA = game.addUnit("Scout", civ, tile(0))
        val scoutB = game.addUnit("Scout", civ, tile(10))
        see()
        val one = ExplorationPlots(civ, listOf(scoutA))
        assertEquals(near, one.targetGoodyPlot(scoutA))
        val two = ExplorationPlots(civ, listOf(scoutA, scoutB))
        assertEquals(near, two.targetGoodyPlot(scoutA))
        assertEquals(far, two.targetGoodyPlot(scoutB))
        // an unrevealed hut is not one: the economic AI only knows revealed plots
        far.setExplored(civ, false)
        see()
        assertEquals(listOf(near), ExplorationPlots(civ, listOf(scoutB)).goodyPlots)
    }

    @Test
    fun `an explorer ends its turn on the reachable plot with the best explore score`() {
        exploreOnlyAround(tile(0), 2)
        // The corners of the ring of plots at distance 2 touch three unexplored plots, the other plots of it two: hills (+50) on a corner is the best
        val hills = game.setTileTerrain(tile(2).position, Constants.hill)
        val scout = game.addUnit("Scout", civ, tile(0))
        see()
        HomelandAi.doTurn(civ, listOf(scout), AutomationCache())
        assertEquals(hills, scout.getTile())
    }

    @Test
    fun `a land explorer with no target and no way home is disbanded, and recon keeps counting it so it asks for no replacement`() {
        exploreOnlyAround(tile(0), 6)
        game.addCity(civ, tile(4))
        val scout = game.addUnit("Scout", civ, tile(0))
        for (neighbor in tile(0).neighbors) game.setTileTerrain(neighbor.position, Constants.ocean)
        civ.ai.economic.doReconState(civ)
        assertEquals("with its explorer alive recon is not needed", ReconState.Neutral, civ.ai.economic.reconState)
        see()
        HomelandAi.doTurn(civ, listOf(scout), AutomationCache())
        assertTrue(scout.isDestroyed)
        civ.ai.economic.doReconState(civ)
        assertEquals("the disbanded explorer still counts", ReconState.Neutral, civ.ai.economic.reconState)
    }

    // endregion
    // region Upgrades and the rest

    @Test
    fun `the unit next in line to upgrade goes next to our nearest city when it stands outside our territory`() {
        civ.tech.addTechnology("Bronze Working")
        civ.tech.addTechnology("Civil Service")
        val city = game.addCity(civ, tile(0))
        val outside = game.addUnit("Spearman", civ, tile(4))
        assertNotEquals(civ, outside.getTile().getOwner())
        val cache = run(outside)
        assertTrue(outside in cache.processedUnits)
        assertEquals("on its way to a tile beside the city", 1, outside.getMovementDestination().aerialDistanceTo(city.getCenterTile()))
        assertTrue("and the gold for its upgrade is being saved", civ.ai.purchaseSavings.requests.isNotEmpty())
        assertTrue(outside.isMoving() || distance(outside, city.getCenterTile()) == 1)
    }

    @Test
    fun `a unit with nothing to do steps to a neighboring tile of our own territory`() {
        val city = game.addCity(civ, tile(0))
        val garrison = game.addUnit("Archer", civ, tile(0))
        val border = city.getTiles().first { it != city.getCenterTile() && it.neighbors.any { n -> n.getOwner() != civ } }
        val spare = game.addUnit("Archer", civ, border) // ranged: no sentry, and the city has its garrison
        val cache = run(garrison, spare)
        assertTrue(spare in cache.processedUnits)
        assertNotEquals(border, spare.getTile())
        assertEquals(civ, spare.getTile().getOwner())
    }

    @Test
    fun `units that cannot be moved by the homeland AI are not recruited`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        val processed = game.addUnit("Warrior", civ, tile(1))
        val spent = game.addUnit("Warrior", civ, tile(2)).also { it.currentMovement = 0f }
        val free = game.addUnit("Warrior", civ, tile(3))
        val cache = AutomationCache().also { it.processedUnits.add(processed) }
        assertEquals(listOf(free), HomelandAi.recruitUnits(civ, listOf(worker, processed, spent, free), cache))
        assertFalse(worker in cache.processedUnits)
    }

    // endregion
}
