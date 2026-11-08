package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.ArmySlot
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationMoveType
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.view.GameView

/**
 *  INTERIM executor of the armies of the AI operations: the movement part of CvTacticalAI::PlotOperationalArmyMoves
 *  (PlotSingleHexOperationMoves, PlotEnemyTerritoryOperationMoves). The tactical port replaces this object (formation positions, the
 *  deployment plot scoring, clearing the enemies near the army, danger-aware paths); it touches nothing but the operation's armies through
 *  [com.unciv.logic.automation.strategy.AiOperations], so the operation model stays as it is.
 *
 *  What it does, in the spirit of the original:
 *  - Recruiting: units that exist head for the muster point once they would otherwise arrive after the army's expected muster turn
 *    (CvArmyAI::UpdateCheckpointTurns); until then they are free for other work. With no estimate for the whole army nobody moves.
 *  - Gathering: every unit goes to within the gather range of the muster point and holds there.
 *  - Moving: the army's checkpoint this turn is the tile where the unit furthest behind ends this turn's path to the goal; units further
 *    along wait for it, the others head there - the army advances at the slowest unit's pace. Units attack enemies in reach on the way.
 *  - A settler with its escort (single hex): the escort comes to the settler, then both go to the end of the settler's path this turn.
 *  Moving armies are the tactical AI's to command, so the units are marked processed ([AutomationCache.processedUnits]).
 */
object OperationMoves {

    /** CvPlayer::doTurnUnits' operational part: units lost, uncommitted cities, the sweep, then the armies move ([moveOperation]) */
    fun doTurn(civ: Civilization, cache: AutomationCache) = timeThis("AI.OperationMoves") {
        val operations = civ.ai.operations
        operations.reconcileUnits()
        operations.refreshCommitments()
        // EconomicAIHelpers::IsTestStrategy_FoundCity: every loose settler gets an operation
        if (civ.cities.isNotEmpty())
            for (unit in civ.units.getCivUnits().toList())
                if ((civ.isAI() || unit.isAutomated()) && SettlerAutomation.isLooseSettler(unit)) SettlerAutomation.launchOperation(unit)
        operations.sweep()
        for (operation in operations.operations.toList()) moveOperation(civ, operation, cache)
        operations.sweep()
    }

    /** CvTacticalAI::PlotOperationalArmyMoves for one operation: its armies move once a turn, then CheckOnTarget */
    fun moveOperation(civ: Civilization, operation: AiOperation, cache: AutomationCache) {
        val turn = civ.gameInfo.turns
        if (operation.lastTurnMoved >= turn || operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return
        when (operation.type.moveType) {
            OperationMoveType.SingleHex -> moveSingleHex(civ, operation, cache)
            OperationMoveType.EnemyTerritory -> moveEnemyTerritory(civ, operation, cache)
            OperationMoveType.None -> Unit
        }
        operation.lastTurnMoved = turn
        AiOperationLogic.checkOnTarget(civ, operation)
    }

    private fun hold(unit: MapUnit, cache: AutomationCache) {
        unit.fortifyIfCan()
        cache.processedUnits.add(unit)
    }

    /** MISSION_MOVE_TO for an army unit: without a path (a ship sent at a land tile, a unit cut off by water) the move fails and the unit stays */
    private fun moveToward(unit: MapUnit, tile: Tile, cache: AutomationCache) {
        cache.processedUnits.add(unit)
        if (unit.getTile() == tile || !unit.hasMovement() || !unit.movement.canReach(tile)) return
        unit.movement.headTowards(tile)
    }

    private fun tileAt(civ: Civilization, coord: com.unciv.logic.map.HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    //region Single hex: settlers and their escort (PlotSingleHexOperationMoves)

    private fun moveSingleHex(civ: Civilization, operation: AiOperation, cache: AutomationCache) {
        val army = operation.firstArmy ?: return
        val units = AiOperationLogic.armyUnits(civ, army)
        val civilian = units.firstOrNull { it.first == 0 }?.second ?: return
        val escort = units.firstOrNull { it.first != 0 }?.second
        val target = AiOperationLogic.targetTile(civ, operation) ?: return

        // Escort and civilian meeting up
        if (operation.state == OperationState.RECRUITING_UNITS || operation.state == OperationState.GATHERING_FORCES) {
            if (escort == null) return // recruiting: the settler waits
            val meeting = civilian.getTile()
            if (escort.getTile() == meeting) { cache.processedUnits.add(escort); return }
            if (escort.movement.canReach(meeting)) {
                escort.movement.headTowards(meeting)
                cache.processedUnits.add(escort)
                return
            }
            // Find a new place to meet up: an empty tile next to the civilian both can reach
            val place = meeting.neighbors.firstOrNull { it.getUnits().none() && escort.movement.canReach(it) && civilian.movement.canReach(it) }
            if (place != null) {
                escort.movement.headTowards(place); civilian.movement.headTowards(place)
                cache.processedUnits.add(escort); cache.processedUnits.add(civilian)
            } else retargetAndFinish(civ, operation, army, civilian, escort)
            return
        }

        // Moving to the target
        if (civilian.getTile() == target) return // CheckOnTarget founds the city
        if (civilian.getTile().militaryUnit?.civ != civ && CivilianSafety.fleeIfThreatened(civilian)) { cache.processedUnits.add(civilian); return }
        val path = civilian.movement.getShortestPath(target)
        if (path.isEmpty()) { retargetAndFinish(civ, operation, army, civilian, escort); return }
        val next = path.first()
        if (escort == null) {
            CivilianSafety.headTowardsAvoidingDanger(civilian, target)
            cache.processedUnits.add(civilian)
            return
        }
        // See if the escort can move to the same location in one turn
        if (escort.getTile() == next || escort.movement.canReachInCurrentTurn(next)) {
            if (escort.getTile() != next) escort.movement.moveToTile(next)
            civilian.movement.moveToTile(next)
        } else {
            // The escort's own way: find where it ends this turn and see whether the civilian can come along
            val escortNext = escort.movement.getShortestPath(target).firstOrNull()
            if (escortNext == null || !civilian.movement.canReachInCurrentTurn(escortNext)) {
                retargetAndFinish(civ, operation, army, civilian, escort)
                return
            }
            escort.movement.moveToTile(escortNext)
            civilian.movement.moveToTile(escortNext)
        }
        cache.processedUnits.add(escort); cache.processedUnits.add(civilian)
    }

    private fun retargetAndFinish(civ: Civilization, operation: AiOperation, army: AiArmy, civilian: MapUnit, escort: MapUnit?) {
        AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id}: no way to ${operation.targetTile}, retargeting" }
        AiOperationLogic.retargetCivilian(civ, operation, army, civilian)
        civilian.currentMovement = 0f
        escort?.currentMovement = 0f
    }
    //endregion

    //region Armies in enemy territory (PlotEnemyTerritoryOperationMoves)

    private fun moveEnemyTerritory(civ: Civilization, operation: AiOperation, cache: AutomationCache) {
        val operations = civ.ai.operations
        for (army in operation.armies.toList()) {
            val muster = AiOperationLogic.musterTile(civ, operation) ?: continue
            updateCheckpointTurns(civ, army, muster)
            when (army.state) {
                ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> recruitingMoves(civ, operation, army, muster, cache)
                ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> gatherMoves(civ, army, muster, cache)
                ArmyState.MOVING_TO_DESTINATION -> if (!advance(civ, operation, army, cache))
                    operations.abort(operation, OperationAbortReason.LOST_PATH)
            }
            AiOperationLogic.armyMoved(civ, operation, army)
        }
    }

    /** CvArmyAI::UpdateCheckpointTurns: when each unit that exists will be at the muster point */
    private fun updateCheckpointTurns(civ: Civilization, army: AiArmy, muster: Tile) {
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            val turns = AiOperationLogic.turnsToReach(unit, muster)
            if (turns != Int.MAX_VALUE) civ.ai.operations.setEstimatedTurn(army, index, turns)
        }
    }

    /** CvArmyAI::GetTurnAtNextCheckpoint: the latest expected arrival, [ArmySlot.UNKNOWN_TURN] if a slot has no estimate */
    private fun turnAtNextCheckpoint(army: AiArmy): Int {
        var result = ArmySlot.NOT_INCLUDED
        for (slot in army.slots) {
            if (slot.estimatedTurn == ArmySlot.UNKNOWN_TURN) return ArmySlot.UNKNOWN_TURN
            if (slot.estimatedTurn > result) result = slot.estimatedTurn
        }
        return result
    }

    /** Units head for the muster point once they are just able to get there in time */
    private fun recruitingMoves(civ: Civilization, operation: AiOperation, army: AiArmy, muster: Tile, cache: AutomationCache) {
        val checkpoint = turnAtNextCheckpoint(army)
        if (checkpoint == ArmySlot.UNKNOWN_TURN) return // let the rest of the AI use these units
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            if (cache.processedUnits.contains(unit)) continue
            val slot = army.slots[index]
            if (!slot.startedOnOperation) {
                val turns = AiOperationLogic.turnsToReach(unit, muster)
                if (turns == Int.MAX_VALUE || turns + civ.gameInfo.turns < checkpoint) continue
                slot.startedOnOperation = true
            }
            gatherUnit(civ, army, unit, muster, cache)
        }
    }

    private fun gatherMoves(civ: Civilization, army: AiArmy, muster: Tile, cache: AutomationCache) {
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            if (cache.processedUnits.contains(unit)) continue
            army.slots[index].startedOnOperation = true
            gatherUnit(civ, army, unit, muster, cache)
        }
    }

    /** A unit within the gather range of the muster point holds; others head there */
    private fun gatherUnit(civ: Civilization, army: AiArmy, unit: MapUnit, muster: Tile, cache: AutomationCache) {
        if (unit.getTile().aerialDistanceTo(muster) <= AiOperationLogic.gatherTolerance(civ, army)) hold(unit, cache)
        else moveToward(unit, muster, cache)
    }

    /**
     *  The army advances: the tile where the unit furthest behind ends this turn's path to the goal is the checkpoint (ComputeCenterOfMassForTurn's
     *  node ahead of the army); units that are already closer to the goal wait, the rest go there, attacking what they can on the way.
     *  @return false if no path to the goal exists (the operation aborts, AI_ABORT_LOST_PATH)
     */
    private fun advance(civ: Civilization, operation: AiOperation, army: AiArmy, cache: AutomationCache): Boolean {
        val goal = tileAt(civ, army.goalTile) ?: return false
        val units = AiOperationLogic.armyUnits(civ, army).map { it.second }.filter { !cache.processedUnits.contains(it) }
        if (units.isEmpty()) return true
        val lead = units.maxBy { it.getTile().aerialDistanceTo(goal) }
        val checkpoint: Tile = if (lead.getTile() == goal) goal else {
            val path = lead.movement.getShortestPath(goal)
            if (path.isEmpty()) return false
            path.first()
        }
        army.position = checkpoint.position
        val checkpointDistance = checkpoint.aerialDistanceTo(goal)
        for (unit in units) {
            if (unit.isMilitary() && unit.health >= 50) {
                val view = GameView(civ.gameInfo, civ).getMapUnitView(unit)
                if (BattleHelper.tryAttackNearbyEnemy(view)) { cache.processedUnits.add(unit); continue }
            }
            if (unit.getTile().aerialDistanceTo(goal) <= checkpointDistance) hold(unit, cache)
            else moveToward(unit, checkpoint, cache)
        }
        return true
    }
    //endregion

    @Suppress("unused")
    private val interimTypes = AiOperationType.entries
}
