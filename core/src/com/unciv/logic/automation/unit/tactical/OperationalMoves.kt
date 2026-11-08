package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ArmySlot
import com.unciv.logic.automation.strategy.ArmyState
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.OperationMoveType
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.automation.unit.SettlerAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import kotlin.math.max
import kotlin.math.min

/**
 *  The movement of the armies of the AI operations: CvTacticalAI::PlotOperationalArmyMoves (the TACTICAL_MOVE_OPERATIONS move) with
 *  PlotSingleHexOperationMoves (a settler and its escort), PlotEnemyTerritoryOperationMoves (armies against a city or camp), ExecuteGatherMoves,
 *  ExecuteFormationMoves, ClearEnemiesNearArmy, MoveWithFormation, and the army's part of CvAIOperation::ComputeCenterOfMassForTurn and
 *  CvArmyAI (movement rate, checkpoint turns). The operation model is [com.unciv.logic.automation.strategy.AiOperations]; the hexes the units
 *  take are scored by [Deployment]. Armies of the naval move types move by [NavalOperationMoves], which U6 adds as the `when` of [moveOperation].
 *
 *  An army that is recruiting sends its units to the muster point once they are just able to get there in time; gathering, everyone goes to hexes
 *  around the muster point (scored for safety and terrain, the gather range for the army's size, or 3); moving, the army's center of mass is
 *  pushed along the route to its goal by the average movement of its units plus 1 hex, the units take hexes around that point (melee out front,
 *  ranged behind; the hexes nearer the rear are the safe ones) and the units that are slow arrive later. Before it moves, the enemies next to the
 *  army's position are attacked by the army's units. An army with no room to gather or move aborts (NO_ROOM_DEPLOY), one with no route to its goal
 *  too (LOST_PATH).
 *
 *  Deviations:
 *  - the original's attack operations move only their first army, as here;
 *  - the route of the center of mass is [com.unciv.logic.automation.strategy.StepFinder]'s (own and allowed land, units ignored);
 *  - TurnsToReachTarget(unit, muster) for the checkpoint turns is [AiOperationLogic.turnsToReach] (the unit's path, units not ignored);
 *  - for a settler's escort the original asks the settler for the end of the escort's path (a slip: it reads the settler's own path);
 *    the end of the escort's path is used;
 *  - CvAIOperation::ArmyMoved is never called by the original and is not either;
 *  - a unit that has been placed is marked processed.
 */
object OperationalMoves {

    /**
     *  What CvPlayer::doTurnUnits does for the operations before the units move: units that died leave their armies, cities that stopped
     *  building for a slot uncommit, every loose settler gets its operation (EconomicAIHelpers::IsTestStrategy_FoundCity), operations that
     *  are over are swept.
     */
    fun prepareTurn(civ: Civilization) = timeThis("AI.OperationsPrepare") {
        val operations = civ.ai.operations
        operations.reconcileUnits()
        operations.refreshCommitments()
        if (civ.cities.isNotEmpty())
            for (unit in civ.units.getCivUnits().toList())
                if ((civ.isAI() || unit.isAutomated()) && SettlerAutomation.isLooseSettler(unit)) SettlerAutomation.launchOperation(unit)
        operations.sweep()
    }

    /**
     *  CvTacticalAI::PlotOperationalArmyMoves: every operation that has not moved this turn moves its armies, then CheckOnTarget; then the
     *  operations that are over are removed.
     */
    fun plotOperationalArmyMoves(ctx: TacticalContext) = timeThis("AI.OperationMoves") {
        val civ = ctx.civ
        for (operation in civ.ai.operations.operations.toList()) moveOperation(civ, operation, ctx.cache, ctx)
        civ.ai.operations.sweep()
    }

    /**
     *  One operation's turn: its armies move ([moveSingleHex] or [moveEnemyTerritory] by its move type, naval move types by
     *  [com.unciv.logic.automation.unit.NavalOperationMoves]), then it is checked on its target. [ctx]
     *  is the tactical turn's; one is made if it is not given and the operation needs one.
     */
    fun moveOperation(civ: Civilization, operation: AiOperation, cache: AutomationCache, ctx: TacticalContext? = null) {
        val turn = civ.gameInfo.turns
        if (operation.lastTurnMoved >= turn || operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return
        when (operation.type.moveType) {
            OperationMoveType.SingleHex -> moveSingleHex(ctx ?: lightContext(civ, cache), operation)
            OperationMoveType.EnemyTerritory -> moveEnemyTerritory(ctx ?: fullContext(civ, cache), operation)
            OperationMoveType.NavalEscort, OperationMoveType.FreeformNaval, OperationMoveType.NavalFleet ->
                com.unciv.logic.automation.unit.NavalOperationMoves.moveOperation(civ, operation, cache)
            OperationMoveType.None -> Unit
        }
        operation.lastTurnMoved = turn
        AiOperationLogic.checkOnTarget(civ, operation)
    }

    /** A context for the moves that look at no targets (a settler's escort): the analysis map and no targets */
    private fun lightContext(civ: Civilization, cache: AutomationCache) =
        TacticalContext(civ, civ.ai.tactical.map, civ.ai.plotDanger, cache, emptyList(), TacticalTargets.none())

    /** A context with this turn's targets, for an army that has to attack what is near it */
    private fun fullContext(civ: Civilization, cache: AutomationCache) =
        civ.ai.tactical.map.let { map -> TacticalContext(civ, map, civ.ai.plotDanger, cache, emptyList(), TacticalTargets.find(civ, map, civ.ai.plotDanger)) }

    private fun tileAt(civ: Civilization, coord: HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    /** CvUnit::TurnProcessed: something already decided this unit's moves */
    private fun isProcessed(ctx: TacticalContext, unit: MapUnit) = unit in ctx.cache.processedUnits

    //region Single hex: settlers and their escort (PlotSingleHexOperationMoves)

    /** CvTacticalAI::PlotSingleHexOperationMoves: a civilian and its escort meet, then move to the target together, one hex stack */
    private fun moveSingleHex(ctx: TacticalContext, operation: AiOperation) {
        val civ = ctx.civ
        val army = operation.firstArmy ?: return
        val units = AiOperationLogic.armyUnits(civ, army)
        val civilian = units.firstOrNull()?.second ?: return
        val escort = units.getOrNull(1)?.second
        val target = AiOperationLogic.targetTile(civ, operation) ?: return

        // ESCORT AND CIVILIAN MEETING UP
        if (army.state == ArmyState.WAITING_FOR_UNITS_TO_REINFORCE || army.state == ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP) {
            // Escort died or was poached for other tactical action, the operation will clean itself up in CheckOnTarget
            if (escort == null || isProcessed(ctx, escort)) return
            val meeting = civilian.getTile()
            // Check to make sure escort can get to civilian
            if (escort.getTile() == meeting || escort.movement.canReach(meeting)) {
                // He can, so have civilian remain in place
                Deployment.executeMoveToPlot(ctx, civilian, meeting)
                if (army.numSlotsFilled() > 1) {
                    Deployment.executeMoveToPlot(ctx, escort, meeting)
                    ctx.log { "moving escorting ${escort.name} to civilian ${civilian.name} for operation, ${meeting.position}" }
                }
                return
            }
            // Find a new place to meet up, look at all hexes adjacent to civilian: somewhere both can move and be empty of other units
            for (place in meeting.neighbors) {
                if (place.getUnits().any() || !escort.movement.canMoveTo(place)) continue
                if (escort.movement.canReach(place) && civilian.movement.canReach(place)) {
                    Deployment.executeMoveToPlot(ctx, escort, place)
                    Deployment.executeMoveToPlot(ctx, civilian, place)
                    ctx.log { "moving ${escort.name} and ${civilian.name} to open hex ${place.position}" }
                    return
                }
            }
            // Didn't find an alternative, must abort operation
            retarget(ctx, operation, army, civilian, escort, "no empty tile adjacent to civilian to meet")
            return
        }

        // MOVING TO TARGET: if we're not there yet, we have work to do (otherwise CheckOnTarget will finish the operation for us)
        if (civilian.getTile() == target) return
        // Handle the case of no path found at all for the civilian
        val civilianPath = civilian.movement.getShortestPath(target)
        if (civilianPath.isEmpty()) {
            retarget(ctx, operation, army, civilian, escort, "path lost to target")
            return
        }
        val civilianMove = civilianPath.first()
        var saveMoves = civilianMove == target
        if (army.numSlotsFilled() == 1 || escort == null) {
            // The original's path finder sees danger only for automated (human) units: the AI's civilian keeps to its path
            Deployment.executeMoveToPlot(ctx, civilian, civilianMove, saveMoves)
            ctx.log { "moving ${civilian.name} without escort to ${civilian.getTile().position}" }
            return
        }

        // See if escort can move to the same location in one turn
        if (Deployment.turnsToReachTile(escort, civilianMove) <= 1) {
            Deployment.executeMoveToPlot(ctx, civilian, civilianMove, saveMoves)
            Deployment.executeMoveToPlot(ctx, escort, civilianMove)
            ctx.log { "moving ${civilian.name} and escort ${escort.name} to ${civilianMove.position}" }
            return
        }
        val blocking = civilianMove.militaryUnit?.takeIf { it.civ == civ }
        // See if friendly blocking unit is ending the turn there, or if no blocking unit (which indicates this is somewhere the civilian can
        // move that the escort can't - like minor civ territory), then find a new path based on moving the escort
        if (ctx.map.isFriendlyTurnEndTile(civilianMove) || blocking == null) {
            val escortPath = escort.movement.getShortestPath(target)
            if (escortPath.isEmpty()) {
                retarget(ctx, operation, army, civilian, escort, "path lost to target for the escort")
                return
            }
            val escortMove = escortPath.first()
            saveMoves = escortMove == target
            // See if civilian can move to the same location in one turn
            if (Deployment.turnsToReachTile(civilian, escortMove) <= 1) {
                Deployment.executeMoveToPlot(ctx, escort, escortMove)
                Deployment.executeMoveToPlot(ctx, civilian, escortMove, saveMoves)
                ctx.log { "moving escort ${escort.name} and ${civilian.name} to ${escortMove.position}" }
            } else retarget(ctx, operation, army, civilian, escort, "too many blocking units")
            return
        }
        // Looks like we should be able to move the blocking unit out of the way
        if (TacticalMoves.executeMoveOfBlockingUnit(ctx, blocking)) {
            Deployment.executeMoveToPlot(ctx, escort, civilianMove)
            Deployment.executeMoveToPlot(ctx, civilian, civilianMove, saveMoves)
            ctx.log { "moving escort ${escort.name} and ${civilian.name} to ${civilianMove.position}" }
        } else retarget(ctx, operation, army, civilian, escort, "could not move blocking unit")
    }

    /** CvAIEscortedOperation::RetargetCivilian, and both units are done for the turn */
    private fun retarget(ctx: TacticalContext, operation: AiOperation, army: AiArmy, civilian: MapUnit, escort: MapUnit?, reason: String) {
        AiDecisionLog.log(ctx.civ) { "operation ${operation.type} #${operation.id}: retargeting the civilian, $reason (${operation.targetTile})" }
        AiOperationLogic.retargetCivilian(ctx.civ, operation, army, civilian)
        civilian.currentMovement = 0f
        escort?.currentMovement = 0f
        ctx.unitProcessed(civilian, false)
        escort?.let { ctx.unitProcessed(it) }
    }
    //endregion

    //region Armies against a city or camp (PlotEnemyTerritoryOperationMoves)

    /** CvArmyAI::GetMovementRate: the average base movement of the army's units */
    fun movementRate(civ: Civilization, army: AiArmy): Int {
        val units = AiOperationLogic.armyUnits(civ, army)
        if (units.isEmpty()) return 0
        return (units.sumOf { it.second.baseUnit.movement } + units.size / 2) / units.size
    }

    /**
     *  CvAIOperation::ComputeCenterOfMassForTurn while the army moves to its target: the army's center of mass pushed along the route to its
     *  goal by the army's movement rate + 1 hexes; the second tile is the one two hexes behind it on the route (the army's current center
     *  as far as the formation is concerned). Null if the operation is not moving or there is no route (the operation aborts).
     */
    fun computeCenterOfMassForTurn(civ: Civilization, operation: AiOperation, army: AiArmy): Pair<Tile, Tile>? {
        if (operation.state != OperationState.MOVING_TO_TARGET) return null
        val goal = tileAt(civ, army.goalTile) ?: return null
        val centerOfMass = AiOperationLogic.centerOfMass(civ, army) ?: return null
        // Push the center of mass forward a number of hexes equal to the average movement + 1
        val path = civ.ai.military.steps.path(civ, AiOperationLogic.enemy(civ, operation), centerOfMass, goal) ?: return null
        val forward = min(movementRate(civ, army) + 1, path.size - 1)
        return path[forward] to path[max(forward - 2, 0)]
    }

    /** CvArmyAI::GetTurnAtNextCheckpoint: the latest expected arrival, [ArmySlot.UNKNOWN_TURN] if a slot has no estimate */
    fun turnAtNextCheckpoint(army: AiArmy): Int {
        var result = ArmySlot.NOT_INCLUDED
        for (slot in army.slots) {
            if (slot.estimatedTurn == ArmySlot.UNKNOWN_TURN) return ArmySlot.UNKNOWN_TURN
            if (slot.estimatedTurn > result) result = slot.estimatedTurn
        }
        return result
    }

    /** CvArmyAI::UpdateCheckpointTurns: when each unit that exists will be at the army's position */
    fun updateCheckpointTurns(civ: Civilization, army: AiArmy) {
        val position = tileAt(civ, army.position) ?: return
        for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
            val turns = AiOperationLogic.turnsToReach(unit, position)
            if (turns != Int.MAX_VALUE) civ.ai.operations.setEstimatedTurn(army, index, turns)
        }
    }

    /** CvTacticalAI::MoveWithFormation: [unit] is to move as part of the army's formation if it can move */
    private fun moveWithFormation(units: MutableList<OperationUnit>, unit: MapUnit, position: com.unciv.models.ruleset.ai.FormationPosition) {
        if (unit.hasMovement()) units.add(OperationUnit(unit, position))
    }

    /** CvTacticalAI::PlotEnemyTerritoryOperationMoves: the army of an attack operation recruits, gathers, or moves one step towards its target */
    private fun moveEnemyTerritory(ctx: TacticalContext, operation: AiOperation) {
        val civ = ctx.civ
        val army = operation.firstArmy ?: return
        val slotDefs = civ.ai.operations.slotDefs(army)
        val operationUnits = ArrayList<OperationUnit>()
        val generals = ArrayList<MapUnit>()
        updateCheckpointTurns(civ, army)

        when (army.state) {
            // RECRUITING
            ArmyState.WAITING_FOR_UNITS_TO_REINFORCE -> {
                // If no estimate for when recruiting will end, let the rest of the AI use these units
                val checkpoint = turnAtNextCheckpoint(army)
                if (checkpoint == ArmySlot.UNKNOWN_TURN) return
                val muster = AiOperationLogic.musterTile(civ, operation)
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    val slot = army.slots[index]
                    val position = slotDefs[index].position
                    // Continue moving to target
                    if (slot.startedOnOperation) moveWithFormation(operationUnits, unit, position)
                    else if (muster != null) {
                        // See if we are just able to get to muster point in time. If so, time for us to head over there
                        val turns = AiOperationLogic.turnsToReach(unit, muster)
                        if (turns != Int.MAX_VALUE && turns + ctx.turn >= checkpoint) {
                            slot.startedOnOperation = true
                            moveWithFormation(operationUnits, unit, position)
                        }
                    }
                }
                executeGatherMoves(ctx, operation, army, operationUnits)
            }

            // GATHERING FORCES
            ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP -> {
                clearEnemiesNearArmy(ctx, army)
                // Request moves for all units
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    army.slots[index].startedOnOperation = true
                    moveWithFormation(operationUnits, unit, slotDefs[index].position)
                }
                executeGatherMoves(ctx, operation, army, operationUnits)
            }

            // MOVING TO TARGET
            ArmyState.MOVING_TO_DESTINATION -> {
                // Update army's current location
                val (turnTarget, closestCurrent) = computeCenterOfMassForTurn(civ, operation, army) ?: run {
                    civ.ai.operations.abort(operation, OperationAbortReason.LOST_PATH)
                    return
                }
                army.position = turnTarget.position
                clearEnemiesNearArmy(ctx, army)
                // Request moves for all units
                for ((index, unit) in AiOperationLogic.armyUnits(civ, army)) {
                    if (isProcessed(ctx, unit)) continue
                    if (Deployment.isGeneralOrAdmiral(unit)) { if (unit.hasMovement()) generals.add(unit); continue }
                    moveWithFormation(operationUnits, unit, slotDefs[index].position)
                }
                executeFormationMoves(ctx, operation, army, turnTarget, closestCurrent, operationUnits)
            }
        }
        if (generals.isNotEmpty()) Deployment.moveGreatGenerals(ctx, generals, army)
    }

    /**
     *  CvTacticalAI::ClearEnemiesNearArmy: if an enemy unit is on or next to the army's position, the army's units join the tactical units of
     *  this turn and attack the enemies there, safe and protected bombards first, then the attacks where each unit does at least what it takes.
     */
    fun clearEnemiesNearArmy(ctx: TacticalContext, army: AiArmy) {
        val civ = ctx.civ
        val position = tileAt(civ, army.position) ?: return
        val range = 1
        fun isNear(target: TacticalTarget) = target.isUnitTarget && position.aerialDistanceTo(target.tile) <= range
        if (ctx.targets.all.none { isNear(it) }) return

        // Add units from army to tactical AI for this turn
        for ((_, unit) in AiOperationLogic.armyUnits(civ, army))
            if (!isProcessed(ctx, unit) && !unit.isDestroyed && unit.hasMovement()) ctx.addUnit(unit)

        // Now attack these targets
        for (target in ctx.targets.all.filter { isNear(it) }) {
            if (!target.isAlive(civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
            TacticalBombards.executeSafeBombards(ctx, target)
            if (target.isAlive(civ)) TacticalBombards.executeProtectedBombards(ctx, target)
            if (target.isAlive(civ)) {
                val defender = TacticalTargets.visibleEnemyDefender(civ, target.tile) ?: continue
                target.aux = defender.health
                val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1, 0)
                if (strikers.isEmpty()) continue
                TacticalAttacks.computeTotalExpectedDamage(target, strikers)
                TacticalAttacks.executeAttack(ctx, target, strikers, emptyList(), inflictWhatWeTake = true, mustSurviveAttack = true)
            }
        }
    }

    /**
     *  CvTacticalAI::ExecuteGatherMoves: the units asked to move with the formation ([units]) gather around the army's position: everyone as a
     *  melee unit, on hexes within the gather range for their number if there are twice as many hexes as units, else within 3. No room (even
     *  one hex per unit) aborts the operation (NO_ROOM_DEPLOY).
     */
    private fun executeGatherMoves(ctx: TacticalContext, operation: AiOperation, army: AiArmy, units: List<OperationUnit>) {
        if (units.isEmpty()) return
        val civ = ctx.civ
        val target = tileAt(civ, army.position) ?: return
        // Gathering - treat everyone as a melee unit; don't need ranged in the rear yet
        val count = units.size
        // Range around target based on number of units we need to place; try one time with the computed range
        val computedRange = AiOperationLogic.gatherRangeForXUnits(count)
        var scored = Deployment.scoreDeploymentPlots(ctx, operation, army, target, count, 0, computedRange)
        // Did we get twice as many possible plots as units? If there are not enough at all, the same search again would find no more
        if (scored.enough && scored.plots.size < count * 2) scored = Deployment.scoreDeploymentPlots(ctx, operation, army, target, count, 0, 3)
        if (!scored.enough) {
            ctx.log { "operation aborting, army ${army.id}: not enough spaces to deploy near target" }
            civ.ai.operations.abort(operation, OperationAbortReason.NO_ROOM_DEPLOY)
            return
        }
        gatherUnits(ctx, army, units, scored)
    }

    private fun gatherUnits(ctx: TacticalContext, army: AiArmy, units: List<OperationUnit>, scored: DeploymentPlots) {
        // Compute the moves to get the best deployment
        val potential = ArrayList<PotentialBlock>()
        var unitsToPlace = units.size
        val armyUnitIds = army.unitIds()
        for (plot in scored.plots) {
            if (unitsToPlace == 0) break
            // Don't use if there's already a unit not in the army here
            val unitAlreadyThere = plot.tile.militaryUnit
            if (unitAlreadyThere != null && unitAlreadyThere.id !in armyUnitIds) continue
            val candidates = Deployment.findClosestOperationUnits(units, plot.tile, safeForRanged = true, mustBeRanged = false)
            if (candidates.isEmpty()) continue
            potential.addAll(Deployment.blocksFor(plot.tile, candidates))
            unitsToPlace--
        }
        // Now ready to make the assignments
        val chosen = Deployment.assignDeployingUnits(potential, units.size - unitsToPlace)
        Deployment.performChosenMoves(ctx, chosen)
        // Log if someone in army didn't get a move assigned
        if (chosen.size < units.size) ctx.log { "no gather move for ${units.size - chosen.size} units" }
    }

    /**
     *  CvTacticalAI::ExecuteFormationMoves: the units asked to move with the formation ([units]) take the hexes [Deployment.scoreFormationPlots] finds
     *  around the army's goal for the turn, [forwardTarget]: melee units first, the nearest unit to each hex, then the ranged units on the hexes
     *  nearer the rear, then wherever is left. A hex with a unit on it is not used. Not enough hexes aborts the operation (NO_ROOM_DEPLOY).
     */
    private fun executeFormationMoves(ctx: TacticalContext, operation: AiOperation, army: AiArmy, forwardTarget: Tile, currentCenter: Tile, units: List<OperationUnit>) {
        if (units.isEmpty()) return
        val meleeUnits = units.count { !it.unit.baseUnit.isRanged() }
        val rangedUnits = units.size - meleeUnits

        // See if we have enough places to put everyone
        val scored = Deployment.scoreFormationPlots(ctx, operation, army, forwardTarget, currentCenter, units.size)
        if (!scored.enough) {
            ctx.log { "operation aborting, army ${army.id}: not enough spaces to deploy along the formation's path" }
            ctx.civ.ai.operations.abort(operation, OperationAbortReason.NO_ROOM_DEPLOY)
            return
        }

        fun place(plot: DeployPlot, safeForRanged: Boolean, mustBeRanged: Boolean, what: String): Boolean {
            // Don't use if there's already someone here
            if (plot.tile.militaryUnit != null) return false
            val unit = Deployment.findClosestOperationUnits(units, plot.tile, safeForRanged, mustBeRanged).firstOrNull()?.unit ?: return false
            Deployment.moveToUsingSafeEmbark(ctx, unit, plot.tile)
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
            ctx.log { "deploying $what ${unit.name} to ${plot.tile.position}, now at ${unit.getTile().position}" }
            return true
        }

        // First loop for melee units who should be out front
        var meleeToPlace = meleeUnits
        for (plot in scored.plots) {
            if (meleeToPlace == 0) break
            if (place(plot, safeForRanged = false, mustBeRanged = false, what = "melee unit")) meleeToPlace--
        }
        if (meleeToPlace > 0) ctx.log { "no army deployment move for $meleeToPlace melee units" }

        // Second loop for ranged units, on the hexes safe for them, then (third loop) on any hex
        var rangedToPlace = rangedUnits
        for (plot in scored.plots) {
            if (rangedToPlace == 0) break
            if (plot.forRanged && place(plot, safeForRanged = true, mustBeRanged = true, what = "ranged unit")) rangedToPlace--
        }
        for (plot in scored.plots) {
            if (rangedToPlace == 0) break
            if (place(plot, safeForRanged = true, mustBeRanged = true, what = "ranged unit (pass 2)")) rangedToPlace--
        }
        if (rangedToPlace > 0) ctx.log { "no army deployment move for $rangedToPlace ranged units" }
    }
    //endregion
}
