package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ArmySlot
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.NavalOperationLogic
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationMoveType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max
import kotlin.math.min

/**
 *  The movement of the naval armies of CvTacticalAI::PlotOperationalArmyMoves: PlotNavalEscortOperationMoves (invasions),
 *  PlotFreeformNavalOperationMoves (naval superiority) and - as the original's enemy territory type with ships - the
 *  bombardment and the pure naval city attack ([OperationMoveType.NavalFleet]), with ExecuteNavalFormationMoves, ExecuteFleetMoveToTarget and
 *  the formation checkpoint of the moving army. The units of an army are marked processed ([AutomationCache.processedUnits]).
 *
 *  Recruiting: units that exist head for the muster point once they would otherwise arrive after the army's expected muster turn (as
 *  [OperationMoves] does for land armies); gathering: the whole army goes to the muster; moving: the army's checkpoint this turn is the tile
 *  the closest unit would reach if it moved as far as the slowest one does along the sea route to a water tile next to the target, and every unit
 *  takes a tile around it.
 *
 *  Deviations, with the reason: ScoreDeploymentPlots / AssignDeployingUnits / PerformChosenMoves are replaced by a greedy assignment of the
 *  water tiles within the gather range of the checkpoint (ships first, then the land units and admirals, each unit taking the nearest tile
 *  it can use; Unciv allows one military unit per tile, the original stacks escorts on their ships); the original's path flags
 *  (MOVE_UNITS_IGNORE_DANGER) have no counterpart; an invasion that cannot get anywhere aborts with LOST_PATH (the original retargets a
 *  civilian that does not exist).
 */
object NavalOperationMoves {

    /** CvTacticalAI::PlotOperationalArmyMoves for one naval operation */
    fun moveOperation(civ: Civilization, operation: AiOperation, cache: AutomationCache) {
        val army = operation.firstArmy ?: return
        when (operation.type.moveType) {
            OperationMoveType.NavalEscort -> escortMoves(civ, operation, army, cache)
            OperationMoveType.FreeformNaval, OperationMoveType.NavalFleet -> fleetMoves(civ, operation, army, cache)
            else -> Unit
        }
    }

    //region Operations

    /** CvTacticalAI::PlotNavalEscortOperationMoves */
    private fun escortMoves(civ: Civilization, operation: AiOperation, army: AiArmy, cache: AutomationCache) {
        val muster = AiOperationLogic.musterTile(civ, operation) ?: return
        AiOperationLogic.updateCheckpointTurns(civ, army, muster)
        when (army.state) {
            ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> recruitingMoves(civ, operation, army, muster, cache)
            ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> fleetMoveToTarget(civ, operation, army, muster, cache)
            ArmyState.MOVING_TO_DESTINATION -> movingAsArmy(civ, operation, army, cache)
        }
    }

    /** CvTacticalAI::PlotFreeformNavalOperationMoves, and the moves of the fleet operations that the original gives the enemy territory type */
    private fun fleetMoves(civ: Civilization, operation: AiOperation, army: AiArmy, cache: AutomationCache) {
        val muster = AiOperationLogic.musterTile(civ, operation) ?: return
        val freeform = operation.type.moveType == OperationMoveType.FreeformNaval
        AiOperationLogic.updateCheckpointTurns(civ, army, muster)
        when (army.state) {
            ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> recruitingMoves(civ, operation, army, muster, cache)
            ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP ->
                if (freeform) {
                    // Get them moving to target without delay
                    AiOperationLogic.armyInPosition(civ, operation, army)
                    AiOperationLogic.targetTile(civ, operation)?.let { fleetMoveToTarget(civ, operation, army, it, cache) }
                } else fleetMoveToTarget(civ, operation, army, muster, cache)
            ArmyState.MOVING_TO_DESTINATION ->
                if (freeform) {
                    AiOperationLogic.armyInPosition(civ, operation, army)
                    AiOperationLogic.targetTile(civ, operation)?.let { fleetMoveToTarget(civ, operation, army, it, cache) }
                } else movingAsArmy(civ, operation, army, cache)
        }
    }
    //endregion

    //region Recruiting and gathering

    /**
     *  The recruiting branch shared by the naval operations: with no estimate for the whole army the units are the rest of the AI's; units that
     *  are on the operation already, or that would be late if they waited, move with the formation to the muster point.
     */
    private fun recruitingMoves(civ: Civilization, operation: AiOperation, army: AiArmy, muster: Tile, cache: AutomationCache) {
        val checkpoint = AiOperationLogic.turnAtNextCheckpoint(army)
        if (checkpoint == ArmySlot.UNKNOWN_TURN) return
        val moving = ArrayList<MapUnit>()
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            if (unit in cache.processedUnits) continue
            val slot = army.slots[index]
            if (!slot.startedOnOperation) {
                // See if we are just able to get to muster point in time. If so, time for us to head over there
                val turns = AiOperationLogic.turnsToReach(unit, muster)
                if (turns == Int.MAX_VALUE || turns + civ.gameInfo.turns < checkpoint) continue
                slot.startedOnOperation = true
            }
            moving.add(unit)
        }
        formationMoves(civ, operation, army, moving, muster, cache)
    }

    /** CvTacticalAI::ExecuteFleetMoveToTarget: every unit of the army moves with the formation to [target] */
    private fun fleetMoveToTarget(civ: Civilization, operation: AiOperation, army: AiArmy, target: Tile, cache: AutomationCache) {
        val moving = ArrayList<MapUnit>()
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            if (unit in cache.processedUnits) continue
            army.slots[index].startedOnOperation = true
            moving.add(unit)
        }
        formationMoves(civ, operation, army, moving, target, cache)
    }
    //endregion

    //region Moving

    /** Everything of the army ends its turn where it is, and the operation looks for another way (the original's error handling) */
    private fun finishAll(civ: Civilization, army: AiArmy, cache: AutomationCache) {
        for ((_, unit) in AiOperationLogic.armyUnits(civ, army)) {
            unit.currentMovement = 0f
            cache.processedUnits.add(unit)
        }
    }

    /**
     *  The army moves as a formation to the checkpoint on the sea route to a water tile next to the goal (the goal itself when it is water):
     *  the closest unit moves as far as the slowest one can. No such tile or route aborts the operation, LOST_PATH.
     */
    private fun movingAsArmy(civ: Civilization, operation: AiOperation, army: AiArmy, cache: AutomationCache) {
        val goal = AiOperationLogic.tileAt(civ, army.goalTile) ?: return
        val destination = if (goal.isWater) goal else NavalOperationLogic.coastalPlotAdjacentToTarget(civ, goal, army)
        if (destination == null) {
            AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_PATH)
            finishAll(civ, army, cache)
            return
        }
        val units = AiOperationLogic.armyUnits(civ, army).map { it.second }.filter { it !in cache.processedUnits }
        if (units.isEmpty()) return
        // Request moves for all units, getting the slowest movement rate and the closest unit
        val steps = civ.ai.military.steps
        val enemy = AiOperationLogic.enemy(civ, operation)
        var slowest = Int.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        var closest: MapUnit? = null
        for (unit in units) {
            slowest = min(slowest, unit.getMaxMovement())
            // At sea?
            val distance = if (unit.getTile() == destination) 0 else steps.distance(civ, enemy, unit.getTile(), destination, sea = true)
            if (distance >= 0 && distance < bestDistance) { bestDistance = distance; closest = unit }
        }
        // Error handling: no one at sea, retarget
        if (closest == null) {
            AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_PATH)
            finishAll(civ, army, cache)
            return
        }
        // If not close yet, find best plot for this turn's movement along path to ultimate best plot
        var checkpoint = destination
        if (bestDistance > slowest)
            checkpoint = steps.xPlotsFromEnd(civ, enemy, closest.getTile(), destination, bestDistance - max(1, slowest), sea = true) ?: destination
        army.position = checkpoint.position
        formationMoves(civ, operation, army, units, checkpoint, cache)
    }
    //endregion

    //region ExecuteNavalFormationMoves

    private fun moveTo(unit: MapUnit, tile: Tile, cache: AutomationCache) {
        cache.processedUnits.add(unit)
        if (unit.getTile() == tile || !unit.hasMovement()) return
        if (unit.movement.getDistanceToTiles().containsKey(tile) && unit.movement.canMoveTo(tile)) unit.movement.moveToTile(tile)
        else if (unit.movement.canReach(tile)) unit.movement.tryHeadTowards(tile)
    }

    /** The tiles a unit of the group may be put on around [center] within [range]: water (land for land units while the center is on land) that holds no foreign unit */
    private fun deploymentTiles(civ: Civilization, operation: AiOperation, army: AiArmy, center: Tile, range: Int, ownUnits: Set<MapUnit>): List<Tile> {
        val allOcean = NavalOperationLogic.allOceanGoing(civ, army)
        val map = civ.ai.tactical.map
        return center.getTilesInDistance(range).filter { tile ->
            (tile.isWater || !center.isWater) && !tile.isImpassible() && civ.hasExplored(tile) && !(tile.isWater && !allOcean && map.isOcean(tile)) &&
                !map.isEnemyCity(tile) && (tile.militaryUnit == null || tile.militaryUnit in ownUnits)
        }.sortedBy { it.aerialDistanceTo(center) }.toList()
    }

    /**
     *  CvTacticalAI::ExecuteNavalFormationMoves: the ships and the escorted units (land units and Great Admirals) of [units] take places around
     *  [turnTarget] - ships first - within the gather range for the larger group, within 3 if that has too few places; if there are no
     *  places for everyone the operation aborts (AI_ABORT_NO_ROOM_DEPLOY).
     */
    private fun formationMoves(civ: Civilization, operation: AiOperation, army: AiArmy, units: List<MapUnit>, turnTarget: Tile, cache: AutomationCache) {
        if (units.isEmpty()) return
        val ships = units.filter { it.baseUnit.isWaterUnit && !it.baseUnit.isGreatPerson }
        val escorted = units - ships.toSet()
        val own = units.toSet()
        val range = AiOperationLogic.gatherRangeForXUnits(max(ships.size, escorted.size))
        var tiles = deploymentTiles(civ, operation, army, turnTarget, range, own)
        if (tiles.size < units.size) tiles = deploymentTiles(civ, operation, army, turnTarget, 3, own)
        if (tiles.size < units.size) {
            AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id}: no room to deploy ${units.size} units around ${turnTarget.position}" }
            AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_ROOM_DEPLOY)
            return
        }
        val taken = HashSet<Tile>()
        for (group in listOf(ships, escorted)) {
            for (unit in group.sortedBy { it.getTile().aerialDistanceTo(turnTarget) }) {
                val tile = chooseTile(unit, tiles, taken, turnTarget)
                if (tile == null) { cache.processedUnits.add(unit); continue }
                taken.add(tile)
                moveTo(unit, tile, cache)
            }
        }
    }

    /** The place for one unit: where it stands if that is one of the free tiles, else the best it reaches this turn, else the nearest it can head for */
    private fun chooseTile(unit: MapUnit, tiles: List<Tile>, taken: Set<Tile>, center: Tile): Tile? {
        val free = tiles.filter { it !in taken && (it.isWater || !unit.baseUnit.isWaterUnit) }
        if (unit.getTile() in free) return unit.getTile()
        if (!unit.hasMovement()) return null
        val reachable = unit.movement.getDistanceToTiles()
        free.firstOrNull { reachable.containsKey(it) && unit.movement.canMoveTo(it) }?.let { return it }
        return free.sortedBy { unit.getTile().aerialDistanceTo(it) }.take(MAX_HEAD_CANDIDATES).firstOrNull { unit.movement.canReach(it) }
    }

    private const val MAX_HEAD_CANDIDATES = 4
    //endregion
}
