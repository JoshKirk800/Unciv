package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
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
 *  The movement of the armies of the AI operations (CvTacticalAI::PlotOperationalArmyMoves and what it calls): recruiting, gathering around
 *  the muster point, moving along the route with the center of mass pushed forward, the hexes the formation takes, the enemies cleared
 *  from the army's way, the settler and its escort. BNW data, desert land.
 */
@RunWith(GdxTestRunner::class)
class OperationalMovesTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations
    private val turn get() = game.gameInfo.turns

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, tile(0, 0))
        enemyCity = game.addCity(enemy, tile(8, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun unit(name: String, x: Int, y: Int, owner: Civilization = civ): MapUnit = game.addUnit(name, owner, tile(x, y))

    /** One archer and two warriors on the free tiles 3 from the capital: enough for the city attack to start gathering */
    private fun gatheringAttack(): AiOperation {
        val ring = tile(0, 0).getTilesAtDistance(3).filter { it.militaryUnit == null }.toList()
        game.addUnit("Archer", civ, ring[0])
        game.addUnit("Warrior", civ, ring[3])
        game.addUnit("Warrior", civ, ring[6])
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        return operation
    }

    private fun units(operation: AiOperation) = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second }

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList(), TacticalTargets.find(civ, map, danger))
    }

    // region Gathering

    @Test
    fun `an army gathers on hexes within the gather range of its muster point, one unit to a hex, and moves out when it has`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        val muster = AiOperationLogic.musterTile(civ, operation)!!
        OperationalMoves.moveOperation(civ, operation, AutomationCache())

        val tiles = units(operation).map { it.getTile() }
        assertEquals("one to a hex", 3, tiles.toSet().size)
        assertTrue("within the gather range of 2 for three units", tiles.all { it.aerialDistanceTo(muster) <= AiOperationLogic.gatherTolerance(civ, army) })
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertEquals(ArmyState.MOVING_TO_DESTINATION, army.state)
        assertTrue(units(operation).all { it in civ.units.getCivUnits().toSet() })
    }

    @Test
    fun `an army with too few free hexes around its muster point aborts`() {
        val operation = gatheringAttack()
        val muster = AiOperationLogic.musterTile(civ, operation)!!
        // Ocean around the muster point but for the city and one hex: two hexes for three units
        val keep = muster.neighbors.first()
        for (tile in muster.getTilesInDistance(3)) if (tile != muster && tile != keep) game.setTileTerrain(tile.position, "Ocean")
        civ.ai.invalidateTurnCaches()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.NO_ROOM_DEPLOY, operation.abortReason)
    }

    // endregion
    // region Recruiting

    private fun recruiting(): Pair<AiOperation, MapUnit> {
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.BASIC_CITY_ATTACK, enemy, enemyCity, city)
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        val warrior = unit("Warrior", -3, 0)
        ops.addUnit(operation.firstArmy!!, warrior, 1)
        return operation to warrior
    }

    @Test
    fun `an army that does not know when its units will be there leaves the units it has to the rest of the AI`() {
        val (operation, warrior) = recruiting()
        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)
        assertEquals(tile(-3, 0), warrior.getTile())
        assertFalse(warrior in cache.processedUnits)
    }

    @Test
    fun `a recruited unit heads for the muster point when it is just able to get there in time, and not before`() {
        val (operation, warrior) = recruiting()
        val army = operation.firstArmy!!
        // Every other slot is expected in 10 turns: the warrior (2 turns away) has time
        for (index in army.slots.indices) if (index != 1) ops.setEstimatedTurn(army, index, 10)
        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)
        assertEquals("no hurry", tile(-3, 0), warrior.getTile())

        operation.lastTurnMoved = -1
        // Now the army expects its last unit in 2 turns: the warrior is that late already
        for (index in army.slots.indices) if (index != 1) ops.setEstimatedTurn(army, index, 2)
        OperationalMoves.moveOperation(civ, operation, cache)
        assertTrue("it heads for the muster point", warrior.getTile().aerialDistanceTo(tile(0, 0)) < 3)
        assertTrue(warrior in cache.processedUnits)
    }

    // endregion
    // region Moving

    private fun moving(): AiOperation {
        val operation = gatheringAttack()
        for (unit in units(operation)) { unit.removeFromTile(); unit.putInTile(tile(0, 0).neighbors.first { it.militaryUnit == null && !it.isCityCenter() }) }
        operation.state = OperationState.MOVING_TO_TARGET
        operation.firstArmy!!.state = ArmyState.MOVING_TO_DESTINATION
        return operation
    }

    @Test
    fun `the center of mass is pushed along the route by the army's average movement plus one, the formation's current center two hexes behind`() {
        val operation = moving()
        val army = operation.firstArmy!!
        val com = AiOperationLogic.centerOfMass(civ, army)!!
        val goal = game.getTile(army.goalTile!!)
        val rate = OperationalMoves.movementRate(civ, army)
        assertEquals("warriors and archers move 2", 2, rate)
        val (target, current) = OperationalMoves.computeCenterOfMassForTurn(civ, operation, army)!!
        assertEquals(rate + 1, target.aerialDistanceTo(com))
        assertEquals(com.aerialDistanceTo(goal) - (rate + 1), target.aerialDistanceTo(goal))
        assertEquals("two behind", 2, target.aerialDistanceTo(current))
        assertEquals(1, current.aerialDistanceTo(com))
    }

    @Test
    fun `a moving army takes hexes around this turn's goal, one to a hex, and its units are done for the turn`() {
        val operation = moving()
        val army = operation.firstArmy!!
        val goal = game.getTile(army.goalTile!!)
        val centerBefore = AiOperationLogic.centerOfMass(civ, army)!!.aerialDistanceTo(goal)
        val cache = AutomationCache()
        val (target, _) = OperationalMoves.computeCenterOfMassForTurn(civ, operation, army)!!
        OperationalMoves.moveOperation(civ, operation, cache)

        assertEquals("the army's position is this turn's goal", target.position, army.position)
        val all = units(operation)
        assertEquals("one to a hex", 3, all.map { it.getTile() }.toSet().size)
        assertTrue("the army advanced", AiOperationLogic.centerOfMass(civ, army)!!.aerialDistanceTo(goal) < centerBefore)
        assertTrue(all.all { it in cache.processedUnits && !it.hasMovement() })
    }

    @Test
    fun `an army with no route to its goal aborts`() {
        val operation = moving()
        val army = operation.firstArmy!!
        val island = tile(-9, 0)
        for (neighbor in island.neighbors) game.setTileTerrain(neighbor.position, "Ocean")
        army.goalTile = island.position
        civ.ai.invalidateTurnCaches()
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.LOST_PATH, operation.abortReason)
    }

    // endregion
    // region Army deployment hexes

    private fun armyContext(operation: AiOperation) = context(*units(operation).toTypedArray())

    @Test
    fun `gathering hexes score 600 less 100 per hex from the muster, 100 less where the enemy can attack, and 100 more in our city`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        unit("Warrior", 0, 5, enemy) // 2 moves: attacks hexes within 2 of (0,5), passes those within 1
        val ctx = armyContext(operation)
        val scored = Deployment.scoreDeploymentPlots(ctx, operation, army, city.getCenterTile(), 3, 0, 3)
        val byTile = scored.plots.associateBy { it.tile }

        assertEquals("our city: 600 + 100", 700, byTile.getValue(city.getCenterTile()).score)
        assertEquals(500, byTile.getValue(tile(0, -1)).score)
        assertEquals("at 3 from the muster, 2 from the enemy warrior: it can attack there", 600 - 300 - 100, byTile.getValue(tile(0, 3)).score)
        assertFalse(byTile.getValue(tile(0, 3)).forRanged)
        assertTrue(byTile.getValue(tile(0, -3)).forRanged)
        assertEquals(scored.plots.sortedByDescending { it.score }, scored.plots)
    }

    @Test
    fun `gathering needs a hex for every unit and a safe hex for every ranged unit, and does not use hexes held by units that are not the army's`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        val ctx = armyContext(operation)
        assertTrue(Deployment.scoreDeploymentPlots(ctx, operation, army, city.getCenterTile(), 3, 0, 1).enough) // 7 hexes
        assertFalse(Deployment.scoreDeploymentPlots(ctx, operation, army, city.getCenterTile(), 8, 0, 1).enough)
        assertFalse("ranged units need safe hexes", Deployment.scoreDeploymentPlots(ctx, operation, army, city.getCenterTile(), 0, 8, 1).enough)

        val stranger = unit("Warrior", 1, 1, civ) // ours, not in the army
        val ctx2 = armyContext(operation)
        val tiles = Deployment.scoreDeploymentPlots(ctx2, operation, army, city.getCenterTile(), 3, 0, 1).plots.map { it.tile }
        assertFalse(stranger.getTile() in tiles)
        assertTrue(units(operation).none { it === stranger })
    }

    @Test
    fun `formation hexes score 800 less 100 per hex from the goal and from the center, the rear ones are for ranged units`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        val ctx = armyContext(operation)
        val forward = tile(3, 0)
        val current = tile(1, 0)
        val scored = Deployment.scoreFormationPlots(ctx, operation, army, forward, current, 3)
        val byTile = scored.plots.associateBy { it.tile }
        assertTrue(scored.plots.all { it.tile.aerialDistanceTo(forward) <= 3 })
        assertEquals("on the goal, 2 from the center: 800 - 0 - 200", 600, byTile.getValue(forward).score)
        assertFalse(byTile.getValue(forward).forRanged)
        assertEquals("1 from the goal and 1 from the center: 800 - 100 - 100", 600, byTile.getValue(tile(2, 0)).score)
        assertFalse(byTile.getValue(tile(2, 0)).forRanged)
        assertEquals("on the center, 2 from the goal: 800 - 200 - 0, 200 less as a rear hex", 400, byTile.getValue(tile(1, 0)).score)
        assertTrue("nearer the center than the goal is the rear", byTile.getValue(tile(1, 0)).forRanged)
        assertEquals("3 from the goal, 1 from the center, rear: 800 - 300 - 100 - 200", 200, byTile.getValue(tile(1, 1)).score)
        assertTrue("with the goal where the center is, all hexes are rear hexes",
            Deployment.scoreFormationPlots(ctx, operation, army, forward, forward, 3).plots.all { it.forRanged })
    }

    // endregion
    // region ClearEnemiesNearArmy

    @Test
    fun `enemies next to the army's position are attacked by the army's units, which then count as the tactical AI's for the turn`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        army.position = tile(0, 0).position
        val victim = unit("Warrior", 0, 1, enemy)
        victim.health = 20
        // The army's units stand next to the victim
        val placed = listOf(tile(0, 2), tile(1, 2), tile(-1, 1))
        for ((index, unit) in units(operation).withIndex()) { unit.removeFromTile(); unit.putInTile(placed[index]) }
        val army3 = units(operation)
        val ctx = context()
        OperationalMoves.clearEnemiesNearArmy(ctx, army)
        assertTrue("the victim is hit or dead", victim.isDestroyed || victim.health < 20)
        assertTrue(army3.any { it.attacksThisTurn > 0 })
        assertTrue("army units joined this turn's units", army3.all { it in ctx.units || it in ctx.cache.processedUnits })
    }

    @Test
    fun `enemies farther than one hex from the army's position are not its business`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        army.position = tile(0, 0).position
        val victim = unit("Warrior", 3, 3, enemy)
        val ctx = context()
        OperationalMoves.clearEnemiesNearArmy(ctx, army)
        assertEquals(100, victim.health)
        assertTrue(ctx.units.isEmpty())
    }

    // endregion
    // region From arrival to assault

    @Test
    fun `an army that has arrived makes the enemy city a temporary zone, and the tactical turn after that closes in on it`() {
        val operation = gatheringAttack()
        val army = operation.firstArmy!!
        val target = enemyCity.getCenterTile()
        val ring = target.getTilesAtDistance(5).filter { it.militaryUnit == null && it.getContinent() == target.getContinent() }.toList()
        for ((index, unit) in units(operation).withIndex()) { unit.removeFromTile(); unit.putInTile(ring[index * 2]) }
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        army.position = target.position

        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.isTemporaryZoneCity(enemyCity))
        civ.ai.operations.sweep()

        val soldiers = civ.units.getCivUnits().filter { it.isMilitary() }.toList()
        val before = soldiers.associateWith { it.getTile().aerialDistanceTo(target) }
        for (unit in soldiers) unit.currentMovement = unit.getMaxMovement().toFloat()
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val cache = AutomationCache()
        TacticalAi.automate(civ, soldiers, cache)
        assertTrue("the units that left the operation closed in on the city", soldiers.count { it.getTile().aerialDistanceTo(target) < before.getValue(it) } >= 2)
        assertTrue(soldiers.all { it in cache.processedUnits })
    }

    // endregion
    // region Settler and escort

    private fun fertileLand() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `a settler and its escort move to the end of the settler's path together and are done for the turn`() {
        fertileLand()
        val warrior = unit("Warrior", 1, 0)
        val settler = unit("Settler", -1, 0)
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        val cache = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache)
        assertEquals("the escort came to the settler", settler.getTile(), warrior.getTile())
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)

        operation.lastTurnMoved = -1 // the next turn: movement is back
        for (unit in listOf(settler, warrior)) unit.currentMovement = unit.getMaxMovement().toFloat()
        val start = settler.getTile()
        val target = AiOperationLogic.targetTile(civ, operation)!!
        val cache2 = AutomationCache()
        OperationalMoves.moveOperation(civ, operation, cache2)
        assertNotEquals(start, settler.getTile())
        assertEquals("together", settler.getTile(), warrior.getTile())
        assertTrue(settler.getTile().aerialDistanceTo(target) < start.aerialDistanceTo(target))
        assertTrue(settler in cache2.processedUnits && warrior in cache2.processedUnits)
    }

    // endregion
}
