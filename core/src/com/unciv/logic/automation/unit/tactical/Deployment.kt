package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.strategy.AiArmy
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.automation.unit.GreatPersonRole
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.FormationPosition

/** CvTacticalTarget used as a hex of a deployment (a "target" with the hex's score): [forRanged] stands for the original's HIGH_PRIORITY_UNIT type */
class DeployPlot(val tile: Tile, val score: Int, val forRanged: Boolean)

/** The hexes found by a scoring routine and whether there are enough of them (the return value of CvTacticalAI::ScoreDeploymentPlots etc.) */
class DeploymentPlots(val plots: List<DeployPlot>, val enough: Boolean)

/** CvOperationUnit: a unit that moves with a formation, and where it stands in it */
class OperationUnit(val unit: MapUnit, val position: FormationPosition)

/** CvBlockingUnit: [unit] could take [tile], [distance] away from it; [choices] is how many units could take that tile */
class PotentialBlock(val unit: MapUnit, val tile: Tile, val distance: Int, var choices: Int)

/**
 *  Putting the units around a target: CvTacticalAI::ExecuteCloseOnTarget, ExecuteHedgehogDefense, ScoreCloseOnPlots, ScoreHedgehogPlots,
 *  ScoreDeploymentPlots, ScoreFormationPlots, FindClosestOperationUnit, AssignDeployingUnits, PerformChosenMoves, MoveToUsingSafeEmbark,
 *  MoveToEmptySpaceTwoFromTarget, MoveGreatGeneral and ScoreGreatGeneralPlot. The army moves that use them are [OperationalMoves]'.
 *
 *  Units are assigned to hexes the way the original does: hexes in the order of their score, each with the units that can get there, nearest
 *  first; a hex only one unit can take is that unit's, then the best hex takes its nearest unit, until all are placed.
 *  (ChooseRemainingAssignments and ScoreAssignments, the exhaustive matching, are [TacticalBombards.assign]: cover and flank attacks.)
 *
 *  Deviations:
 *  - the deployment score and safe-for-deployment flags of the analysis map cells are only stored by the original, never read; the scores are
 *    in the [DeployPlot]s instead;
 *  - "can reach the hex in N turns" is [turnsToReachTile], a hex held by another unit being out of reach (the original's path finder that
 *    respects units), the distance a unit has to the hex its plot distance;
 *  - MoveToUsingSafeEmbark's "move over land only" flag does not exist in Unciv: a land unit whose turn would end at sea in danger stops on
 *    the reachable land tile nearest to the hex instead, if one is nearer than where it stands, else it takes the risk, as the original does;
 *  - MoveToEmptySpaceTwoFromTarget tests the distance to the target is 2, as its comment says (the original's condition passes the Y test
 *    as an argument of plotDistance by mistake);
 *  - a unit that is placed is marked processed ([TacticalContext.unitProcessed]) where the original leaves it with its moves finished;
 *  - there are no queued attacks (they are made at once, see [TacticalAttacks]), so a general's plot is never near one and no unit is in one.
 */
object Deployment {

    private const val EPSILON = Constants.minimumMovementEpsilon

    /** Plots around a target scored as the original's NUM_CITY_PLOTS ring: the hexes within 3 of it */
    private const val CITY_PLOT_RADIUS = 3

    fun isGeneralOrAdmiral(unit: MapUnit): Boolean {
        val role = GreatPersonAutomation.roleOf(unit.baseUnit, unit.civ.gameInfo.ruleset)
        return role == GreatPersonRole.General || role == GreatPersonRole.Admiral
    }

    /** CvUnit::hasMoved */
    fun hasMoved(unit: MapUnit) = unit.currentMovement < unit.getMaxMovement() - EPSILON

    //region Reach and movement

    /**
     *  TurnsToReachTarget for a hex a unit is to stand on: 0 for the hex it is on, else [TacticalAttacks.turnsToReach]'s turns, [Int.MAX_VALUE]
     *  if it cannot get there or the hex is held by another unit.
     */
    fun turnsToReachTile(unit: MapUnit, tile: Tile): Int {
        if (unit.getTile() == tile) return 0
        if (!unit.movement.canMoveTo(tile)) return Int.MAX_VALUE
        return TacticalAttacks.turnsToReach(unit, tile)
    }

    /**
     *  MISSION_MOVE_TO without the exception: [unit] heads for [tile] as far as it gets this turn.
     *  @return false if it cannot get there (no path, or no movement), the unit then has not moved
     */
    fun headTowards(unit: MapUnit, tile: Tile): Boolean {
        if (unit.getTile() == tile) return true
        if (!unit.hasMovement() || unit.movement.getDistanceToTiles().isEmpty()) return false
        if (!unit.movement.canReachInCurrentTurn(tile) && unit.movement.getShortestPath(tile).isEmpty()) return false
        unit.movement.tryHeadTowards(tile)
        return true
    }

    /**
     *  CvTacticalAI::MoveToUsingSafeEmbark: [unit] heads for [tile]; a land unit that would end its turn on water that enemies can attack
     *  (or in a zone they dominate) stops on land instead if it can. @return whether it was sent
     */
    fun moveToUsingSafeEmbark(ctx: TacticalContext, unit: MapUnit, tile: Tile): Boolean {
        if (!unit.baseUnit.isLandUnit || unit.getTile() == tile) return headTowards(unit, tile)
        val path = unit.movement.getShortestPath(tile)
        if (path.isEmpty()) return false
        if (!path.first().isWater) return headTowards(unit, tile)
        val dangerous = ctx.map.isSubjectToAttack(tile) || ctx.map.isInEnemyDominatedZone(tile)
        if (!dangerous) return headTowards(unit, tile)
        val here = unit.getTile().aerialDistanceTo(tile)
        val landStop = unit.movement.getDistanceToTiles().keys
            .filter { !it.isWater && unit.movement.canMoveTo(it) && it.aerialDistanceTo(tile) < here }
            .minByOrNull { it.aerialDistanceTo(tile) }
        if (landStop != null) {
            unit.movement.moveToTile(landStop)
            return true
        }
        return headTowards(unit, tile)
    }

    /** CvTacticalAI::MoveToEmptySpaceTwoFromTarget: the unit heads for the first empty hex of its domain 2 from [target] that it can reach */
    fun moveToEmptySpaceTwoFromTarget(unit: MapUnit, target: Tile): Boolean {
        val land = !unit.baseUnit.isWaterUnit
        for (tile in target.getTilesInDistance(2)) {
            if (tile.aerialDistanceTo(target) != 2) continue
            if (tile.isWater == land || tile.militaryUnit != null) continue
            // If it is a city, make sure we are friends with them, else we will automatically attack
            if (tile.isCityCenter() && tile.getOwner() != unit.civ) continue
            if (headTowards(unit, tile)) return true
        }
        return false
    }

    /** CvTacticalAI::ExecuteMoveToPlot: [unit] goes to [tile] (or fortifies, or waits, if it is there); unless [saveMoves] it is done for the turn */
    fun executeMoveToPlot(ctx: TacticalContext, unit: MapUnit, tile: Tile, saveMoves: Boolean = false) {
        if (unit.getTile() == tile) {
            if (unit.canFortify()) unit.fortify()
            else if (!saveMoves) unit.currentMovement = 0f
        } else {
            headTowards(unit, tile)
            if (!saveMoves) unit.currentMovement = 0f
        }
        ctx.unitProcessed(unit, unit.isMilitary())
    }

    //endregion
    //region Finding units for hexes

    /**
     *  CvTacticalAI::FindClosestOperationUnit: the [units] that have not moved yet and can get to [tile] (not the ones that shoot unless
     *  [safeForRanged], only those if [mustBeRanged]), the nearest in turns first.
     */
    fun findClosestOperationUnits(units: List<OperationUnit>, tile: Tile, safeForRanged: Boolean, mustBeRanged: Boolean): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (operationUnit in units) {
            val unit = operationUnit.unit
            if (unit.isDestroyed || hasMoved(unit)) continue
            val ranged = unit.baseUnit.isRanged()
            if (!safeForRanged && ranged || mustBeRanged && !ranged) continue
            val turns = turnsToReachTile(unit, tile)
            if (turns == Int.MAX_VALUE) continue
            result.add(Candidate(unit, turns, unit.getTile().aerialDistanceTo(tile)))
        }
        return result.sortedBy { it.turns }
    }

    /** The blocks for one hex: every unit that can take it, each knowing how many there are (CvBlockingUnit::SetNumChoices) */
    fun blocksFor(tile: Tile, candidates: List<Candidate>): List<PotentialBlock> =
        candidates.map { PotentialBlock(it.unit, tile, it.distance, candidates.size) }

    //endregion
    //region AssignDeployingUnits and PerformChosenMoves

    /**
     *  CvTacticalAI::AssignDeployingUnits: from the [potential] blocks (grouped by hex, the best hex first) choose a unit for each hex: first
     *  the hexes only one unit can take, then the nearest unit of the best hex, until every hex has one. [required] is how many units should
     *  be placed. The chosen blocks are returned even where the original reports failure (two hexes that need the same unit, too few units).
     */
    fun assignDeployingUnits(potential: List<PotentialBlock>, required: Int): List<PotentialBlock> {
        val chosen = ArrayList<PotentialBlock>()
        var remaining = potential.toMutableList()

        // The assignments we MUST make (only one possibility)
        while (true) {
            val forced = remaining.filter { it.choices == 1 }
            if (forced.isEmpty()) break
            // The same unit in two forced blocks: not going to work
            if (forced.map { it.unit }.toSet().size < forced.size) return chosen
            chosen.addAll(forced)
            remaining = removeChosenUnits(remaining, forced, 0)
            // Do we have enough units left to cover everything?
            if (numUniqueUnitsLeft(remaining) < required - chosen.size) return chosen
        }

        // Pick the closest unit for the highest priority assignment until all are processed
        while (remaining.isNotEmpty()) {
            val first = remaining[0]
            chosen.add(first)
            // Don't copy the other entries for this hex, so pass in the number of choices here
            remaining = removeChosenUnits(remaining, listOf(first), first.choices)
        }
        return chosen
    }

    /** CvTacticalAI::RemoveChosenUnits: the blocks from [startIndex] on without the units just [newlyChosen], the number of choices per hex counted again */
    private fun removeChosenUnits(blocks: List<PotentialBlock>, newlyChosen: List<PotentialBlock>, startIndex: Int): MutableList<PotentialBlock> {
        val units = newlyChosen.mapTo(HashSet()) { it.unit }
        val result = ArrayList<PotentialBlock>()
        for (index in startIndex until blocks.size) if (blocks[index].unit !in units) result.add(blocks[index])
        val perTile = HashMap<Tile, Int>()
        for (block in result) perTile.merge(block.tile, 1, Int::plus)
        for (block in result) block.choices = perTile.getValue(block.tile)
        return result
    }

    /** CvTacticalAI::NumUniqueUnitsLeft */
    private fun numUniqueUnitsLeft(blocks: List<PotentialBlock>) = blocks.mapTo(HashSet()) { it.unit }.size

    /**
     *  CvTacticalAI::PerformChosenMoves: the chosen units move to their hexes, those near theirs first. First the units that stand where another
     *  is to go, so they make room; then the others; with [fallbackMoveRange] 1 or more those that could not get to their hex move to an empty hex
     *  next to it, with 2 or more to one 2 away. All units placed are done for the turn: they pillage if damaged and on something to pillage,
     *  else fortify.
     */
    fun performChosenMoves(ctx: TacticalContext, chosen: List<PotentialBlock>, fallbackMoveRange: Int = 1) {
        val blocks = chosen.sortedBy { it.distance }
        val moved = BooleanArray(blocks.size)

        // First loop through is for units that have a unit moving into their hex. They need to leave first!
        for ((index, block) in blocks.withIndex()) {
            val unit = block.unit
            if (unit.isDestroyed) continue
            val here = unit.getTile()
            if (here != block.tile && blocks.any { it.tile == here } && isFree(block.tile, unit)) {
                moveToUsingSafeEmbark(ctx, unit, block.tile)
                ctx.log { "deploying ${unit.name} (to get out of the way) to ${block.tile.position} from ${here.position}" }
                moved[index] = true
            }
        }

        // Second loop is for units moving into their chosen spot normally
        for ((index, block) in blocks.withIndex()) {
            val unit = block.unit
            if (unit.isDestroyed) continue
            if (unit.getTile() == block.tile) moved[index] = true
            else if (!moved[index]) {
                val before = unit.getTile()
                moveToUsingSafeEmbark(ctx, unit, block.tile)
                ctx.log { "deploying ${unit.name} to ${block.tile.position} from ${before.position}" }
                if (before != unit.getTile()) moved[index] = true
            }
        }

        // Third loop is for units we still haven't been able to move (other units must be blocking their target for this turn)
        if (fallbackMoveRange > 0) for ((index, block) in blocks.withIndex()) {
            val unit = block.unit
            if (unit.isDestroyed || moved[index]) continue
            val before = unit.getTile()
            if (TacticalMoves.moveToEmptySpaceNearTarget(ctx, unit, block.tile)) {
                ctx.log { "deploying ${unit.name} to space near ${block.tile.position} from ${before.position}" }
                if (before != unit.getTile()) moved[index] = true
            }
        }

        // Fourth loop lets the unit end within 2 of the target
        if (fallbackMoveRange > 1) for ((index, block) in blocks.withIndex()) {
            val unit = block.unit
            if (unit.isDestroyed || moved[index]) continue
            if (moveToEmptySpaceTwoFromTarget(unit, block.tile))
                ctx.log { "deploying ${unit.name} to space within 2 of ${block.tile.position}" }
        }

        // Finish moves for all units
        for (block in blocks) {
            val unit = block.unit
            if (unit.isDestroyed) continue
            if (unit.hasMovement()) {
                val tile = unit.getTile()
                if (UnitActionsPillage.canPillage(unit, tile) && unit.health < 100) {
                    ctx.log { "already in position, will pillage with ${unit.name}, ${tile.position}" }
                    UnitActionsPillage.getPillageAction(unit, tile)?.action?.invoke()
                } else if (unit.canFortify()) unit.fortify()
                unit.currentMovement = 0f
            }
            ctx.unitProcessed(unit)
        }
    }

    /** CvPlot::getNumFriendlyUnitsOfType(unit) == 0 */
    private fun isFree(tile: Tile, unit: MapUnit) = if (unit.isMilitary()) tile.militaryUnit == null else tile.civilianUnit == null

    //endregion
    //region Close on target and hedgehog

    /**
     *  CvTacticalAI::ExecuteCloseOnTarget: the units of the zone's domain within the tactical range of [target] (or within 3 times the deploy
     *  range if they have just left an operation) go to the hexes around it that [scoreCloseOnPlots] finds, ranged units first to those they can
     *  bombard from; generals follow ([moveGreatGenerals]). Ships too in a naval invasion.
     */
    fun executeCloseOnTarget(ctx: TacticalContext, target: Tile, zone: DominanceZone) {
        val deployRange = ctx.rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        val tempZoneTurns = ctx.rules.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
        val radius = ctx.map.tacticalRange
        val operationUnits = ArrayList<OperationUnit>()
        val generals = ArrayList<MapUnit>()
        var ranged = 0
        var melee = 0
        for (unit in ctx.liveUnits()) {
            // If not naval invasion, proper domain of unit?
            if (!(zone.isNavalInvasion || zone.isWater && unit.baseUnit.isWaterUnit || !zone.isWater && unit.baseUnit.isLandUnit)) continue
            // Find units really close to target or somewhat close that just came out of an operation
            val distance = unit.getTile().aerialDistanceTo(target)
            if (distance > radius && !(distance <= deployRange * 3 && ctx.civ.ai.deployFromOperationTurn(unit) + tempZoneTurns >= ctx.turn)) continue
            if (unit.baseUnit.isRanged()) { operationUnits.add(OperationUnit(unit, FormationPosition.BOMBARD)); ranged++ }
            else if (isGeneralOrAdmiral(unit)) generals.add(unit)
            else { operationUnits.add(OperationUnit(unit, FormationPosition.FRONT_LINE)); melee++ }
        }

        if (operationUnits.isNotEmpty()) {
            // Land only unless invasion or no enemy naval presence
            val landOnly = !(zone.isNavalInvasion || zone.enemyNavalUnitCount == 0)
            val plots = scoreCloseOnPlots(ctx, target, landOnly, operationUnits)
            deployUnits(ctx, operationUnits, plots, ranged, melee)
        }
        if (generals.isNotEmpty()) moveGreatGenerals(ctx, generals, null)
    }

    /**
     *  CvTacticalAI::ExecuteHedgehogDefense: the units of the zone's domain within the tactical range of the city [target] take the hexes
     *  [scoreHedgehogPlots] finds around it: ranged units those the enemy cannot attack, the others the rest; generals with moves follow.
     */
    fun executeHedgehogDefense(ctx: TacticalContext, target: Tile, zone: DominanceZone) {
        val radius = ctx.map.tacticalRange
        val operationUnits = ArrayList<OperationUnit>()
        val generals = ArrayList<MapUnit>()
        var ranged = 0
        var melee = 0
        for (unit in ctx.liveUnits()) {
            if (!(zone.isWater && unit.baseUnit.isWaterUnit || !zone.isWater && unit.baseUnit.isLandUnit)) continue
            if (unit.getTile().aerialDistanceTo(target) > radius) continue
            if (isGeneralOrAdmiral(unit) && !unit.baseUnit.isWaterUnit) { if (unit.hasMovement()) generals.add(unit) }
            else if (unit.baseUnit.isRanged()) { operationUnits.add(OperationUnit(unit, FormationPosition.BOMBARD)); ranged++ }
            else { operationUnits.add(OperationUnit(unit, FormationPosition.FRONT_LINE)); melee++ }
        }
        if (operationUnits.isNotEmpty()) deployUnits(ctx, operationUnits, scoreHedgehogPlots(ctx, target), ranged, melee)
        if (generals.isNotEmpty()) moveGreatGenerals(ctx, generals, null)
    }

    /**
     *  The two passes ExecuteCloseOnTarget and ExecuteHedgehogDefense share: the ranged units take the hexes marked for them, then everyone who
     *  is left (the remaining ranged units too) the other hexes, in the order of the hexes' scores.
     */
    private fun deployUnits(ctx: TacticalContext, units: List<OperationUnit>, plots: List<DeployPlot>, rangedUnits: Int, meleeUnits: Int) {
        // First loop for ranged unit spots
        var rangedToPlace = rangedUnits
        var potential = ArrayList<PotentialBlock>()
        for (plot in plots) {
            if (rangedToPlace == 0) break
            if (!plot.forRanged) continue
            val candidates = findClosestOperationUnits(units, plot.tile, safeForRanged = true, mustBeRanged = true)
            if (candidates.isEmpty()) continue
            potential.addAll(blocksFor(plot.tile, candidates))
            rangedToPlace--
        }
        performChosenMoves(ctx, assignDeployingUnits(potential, rangedUnits - rangedToPlace))

        // Second loop for everyone else (including remaining ranged units)
        potential = ArrayList()
        val melee = meleeUnits + rangedToPlace
        var meleeToPlace = melee
        for (plot in plots) {
            if (meleeToPlace == 0) break
            if (plot.forRanged) continue
            val candidates = findClosestOperationUnits(units, plot.tile, safeForRanged = true, mustBeRanged = false)
            if (candidates.isEmpty()) continue
            potential.addAll(blocksFor(plot.tile, candidates))
            meleeToPlace--
        }
        performChosenMoves(ctx, assignDeployingUnits(potential, melee - meleeToPlace))
    }

    /**
     *  CvTacticalAI::ScoreCloseOnPlots: the hexes within 3 of [target] that units may gather on (land only if [landOnly]) with a unit of
     *  [units] within the deploy range of it, scored 600 - 100 per hex of distance, -30 if an enemy can attack it, -30 if one can pass
     *  it, +100 in a city of ours, else + the terrain's defense; those within range of the target but not next to it (the map's flags of
     *  the last bombard, as in the original) are for ranged units. Best first.
     */
    fun scoreCloseOnPlots(ctx: TacticalContext, target: Tile, landOnly: Boolean, units: List<OperationUnit>): List<DeployPlot> {
        val map = ctx.map
        val deployRadius = ctx.rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        val plots = ArrayList<DeployPlot>()
        for (tile in target.getTilesInDistance(CITY_PLOT_RADIUS)) {
            val usable = if (landOnly) map.canUseForOperationGatheringCheckWater(tile, false) else map.canUseForOperationGathering(tile)
            if (!usable) continue
            if (units.none { !it.unit.isDestroyed && it.unit.getTile().aerialDistanceTo(tile) <= deployRadius }) continue
            val distance = tile.aerialDistanceTo(target)
            var score = 600 - distance * 100
            // Top priority is hexes to bombard from (within range but not adjacent)
            val bombardSpot = map.isWithinRangeOfTarget(tile) && distance > 1
            if (map.isSubjectToAttack(tile)) score -= 30
            if (map.enemyCanMovePast(tile)) score -= 30
            score += if (tile.isCityCenter() && tile.getOwner() == ctx.civ) 100 else map.defenseModifier(tile)
            plots.add(DeployPlot(tile, score, bombardSpot))
        }
        return plots.sortedByDescending { it.score }
    }

    /**
     *  CvTacticalAI::ScoreHedgehogPlots: the land hexes within 3 of the city [target] that units may gather on, scored 600 - 150 per hex of
     *  distance, +100 if an enemy can attack it, +50 if one can pass it, +100 in a city of ours, else + 4 times the terrain's defense.
     *  Those no enemy can attack are for ranged units. Best first.
     */
    fun scoreHedgehogPlots(ctx: TacticalContext, target: Tile): List<DeployPlot> {
        val map = ctx.map
        val plots = ArrayList<DeployPlot>()
        for (tile in target.getTilesInDistance(CITY_PLOT_RADIUS)) {
            if (!map.canUseForOperationGatheringCheckWater(tile, false)) continue
            var score = 600 - tile.aerialDistanceTo(target) * 150
            val subjectToAttack = map.isSubjectToAttack(tile)
            if (subjectToAttack) score += 100
            if (map.enemyCanMovePast(tile)) score += 50
            score += if (tile.isCityCenter() && tile.getOwner() == ctx.civ) 100 else map.defenseModifier(tile) * 4
            plots.add(DeployPlot(tile, score, !subjectToAttack))
        }
        return plots.sortedByDescending { it.score }
    }

    //endregion
    //region Army deployment plots

    /** CvArmyAI::IsAllOceanGoing: every unit is a ship or can embark, and can enter the ocean */
    fun isAllOceanGoing(civ: Civilization, army: AiArmy): Boolean = AiOperationLogic.armyUnits(civ, army).all { (_, unit) ->
        if (unit.baseUnit.isLandUnit) civ.tech.unitsCanEmbark && !unit.cache.cannotEmbark && civ.tech.embarkedUnitsCanEnterOcean
        else civ.tech.allUnitsCanEnterOcean || !unit.cache.cannotEnterOceanTiles
    }

    /** Whether the hex is one an army of [operation] may use: land, or water for a mixed land and naval one, not ocean for one that cannot go there */
    private fun isDeployableHex(ctx: TacticalContext, operation: AiOperation, army: AiArmy, tile: Tile, oceanGoing: () -> Boolean): Pair<Boolean, Boolean> {
        val map = ctx.map
        var valid = false
        var forcedToUseWater = false
        if (operation.type.mixedLandNaval) {
            valid = map.canUseForOperationGatheringCheckWater(tile, true)
            if (!oceanGoing() && map.isOcean(tile)) valid = false
        } else if (map.canUseForOperationGatheringCheckWater(tile, false) || SettleAreas.aiMapHint(ctx.civ.gameInfo) and 1 != 0) {
            valid = true
            if (map.isWater(tile)) forcedToUseWater = true
        }
        if (!valid) return false to false
        // Skip this hex if a unit of ours that is not in this army is on it
        val friendly = map.friendlyMilitaryUnit(tile)
        if (friendly != null && friendly.id !in army.unitIds()) return false to false
        return true to forcedToUseWater
    }

    /**
     *  CvTacticalAI::ScoreDeploymentPlots: the hexes within [range] of [target] for the army to gather on: 600 - 100 per hex from the target,
     *  -100 if an enemy can attack it (not safe for ranged units), -100 if one can pass it, +100 in a city of ours, else + twice the terrain's
     *  defense; 10 if it is water an army of land units is forced onto. [DeploymentPlots.enough]: there are as many hexes as units and as many
     *  safe ones as ranged units.
     */
    fun scoreDeploymentPlots(ctx: TacticalContext, operation: AiOperation, army: AiArmy, target: Tile, numMelee: Int, numRanged: Int, range: Int): DeploymentPlots {
        val map = ctx.map
        val oceanGoing = lazy { isAllOceanGoing(ctx.civ, army) }
        val plots = ArrayList<DeployPlot>()
        var safePlots = 0
        for (tile in target.getTilesInDistance(range)) {
            val (valid, forcedToUseWater) = isDeployableHex(ctx, operation, army, tile, { oceanGoing.value })
            if (!valid) continue
            val distance = tile.aerialDistanceTo(target)
            var score = 600 - distance * 100
            val safe = !map.isSubjectToAttack(tile)
            if (safe) safePlots++ else score -= 100
            if (map.enemyCanMovePast(tile)) score -= 100
            score += if (tile.isCityCenter() && tile.getOwner() == ctx.civ) 100 else map.defenseModifier(tile) * 2
            if (forcedToUseWater) score = 10
            plots.add(DeployPlot(tile, score, safe))
        }
        return DeploymentPlots(plots.sortedByDescending { it.score }, !(safePlots < numRanged || plots.size < numMelee + numRanged))
    }

    /**
     *  CvTacticalAI::ScoreFormationPlots: the hexes within 3 of [forwardTarget] (the army's goal this turn) for the army to stand on while it
     *  moves: 800 - 100 per hex from the goal - 100 per hex from [currentCenter]; +100 in a city of ours, else + twice the terrain's defense.
     *  A hex nearer the rear than the goal is safe for ranged units (200 less, so they are not picked first).
     */
    fun scoreFormationPlots(ctx: TacticalContext, operation: AiOperation, army: AiArmy, forwardTarget: Tile, currentCenter: Tile, numUnits: Int): DeploymentPlots {
        val map = ctx.map
        val oceanGoing = lazy { isAllOceanGoing(ctx.civ, army) }
        val plots = ArrayList<DeployPlot>()
        for (tile in forwardTarget.getTilesInDistance(3)) {
            val (valid, forcedToUseWater) = isDeployableHex(ctx, operation, army, tile, { oceanGoing.value })
            if (!valid) continue
            // Score hexes close to the center and the turn target highest
            val distance = tile.aerialDistanceTo(forwardTarget)
            val distanceFromCenter = if (forwardTarget == currentCenter) distance else tile.aerialDistanceTo(currentCenter)
            var score = 800 - distance * 100 - distanceFromCenter * 100
            score += if (tile.isCityCenter() && tile.getOwner() == ctx.civ) 100 else map.defenseModifier(tile) * 2
            if (forcedToUseWater) score = 10
            // Safe if closer to the rear, or if the forward target and the center are the same
            val safe = distanceFromCenter < distance || forwardTarget == currentCenter
            if (safe) score -= 200
            plots.add(DeployPlot(tile, score, safe))
        }
        return DeploymentPlots(plots.sortedByDescending { it.score }, plots.size >= numUnits)
    }

    //endregion
    //region Great generals

    /**
     *  CvTacticalAI::MoveGreatGeneral: each general goes to the hex it can reach this turn that [scoreGreatGeneralPlot] likes best (the army's,
     *  if it is with one, [army]); a general with no hex worth anything stays.
     */
    fun moveGreatGenerals(ctx: TacticalContext, generals: List<MapUnit>, army: AiArmy?) {
        for (general in generals) {
            if (general.isDestroyed) continue
            // Enough to make a decent road move
            val range = general.getMaxMovement() * 3
            val here = general.getTile()
            val candidates = (general.movement.getDistanceToTiles().keys + here).distinct()
                .filter { it.aerialDistanceTo(here) <= range && (it == here || general.movement.canMoveTo(it)) }
                .sortedWith(compareBy({ it.position.x }, { it.position.y }))
            var best: Tile? = null
            var bestScore = -1
            for (tile in candidates) {
                val score = scoreGreatGeneralPlot(ctx, general, tile, army)
                if (score > bestScore && score > 0) { bestScore = score; best = tile }
            }
            val destination = best ?: continue
            executeMoveToPlot(ctx, general, destination)
            ctx.log { "deploying ${general.name} to ${destination.position} from ${here.position}, plot score $bestScore" }
        }
    }

    /**
     *  CvTacticalAI::ScoreGreatGeneralPlot: 0 for a city or a unit that is not ours, or a civilian of ours that is done. Else the strength of the
     *  city of ours, or 250 times that of the unit of ours (a fifth of it if the unit has moves left, 5 times if it is in the same army; a
     *  third for a melee unit about to attack), else 10, plus 5,000,000 within 3 of the army's center of mass or 250,000 per hex short of 20
     *  from it, if the hex is not dangerous, divided by 1 to 5 when the danger is high compared to the strength there (1000 for an empty hex).
     */
    fun scoreGreatGeneralPlot(ctx: TacticalContext, general: MapUnit, tile: Tile, army: AiArmy?): Int {
        val civ = ctx.civ
        val danger = ctx.danger.get(tile)
        var dangerDivisor = 1
        var friendlyUnitFinalPower = 0
        var friendlyCityStrength = 0
        var friendlyCity = false

        // Non-friendly city here?
        if (tile.isCityCenter() && tile.getOwner() != civ) return 0
        // Another player's unit here?
        val unit = tile.getFirstUnit()
        if (unit != null && unit.civ != civ) return 0
        // Another civilian of mine that has finished its moves?
        if (unit != null && unit != general && !TacticalAttacks.canAttack(unit) && hasMoved(unit)) return 0

        val bestDefender = tile.militaryUnit?.takeIf { it.civ == civ }
        if (tile.isCityCenter()) {
            friendlyCity = true
            val city = tile.getCity()!!
            val maxHitPoints = ctx.rules.define(AiDefine.MAX_CITY_HIT_POINTS)
            friendlyCityStrength = CityCombatant(city).getCityStrength() * 100 * city.health / maxHitPoints
            dangerDivisor = dangerDivisorFor(danger, friendlyCityStrength)
        } else if (bestDefender != null && bestDefender != general) {
            val basePower = PlotDanger.strengthConsideringDamage(bestDefender, ctx.rules) * 250
            dangerDivisor = dangerDivisorFor(danger, basePower)
            friendlyUnitFinalPower = basePower
            // Deemphasize if hasn't finished moves yet
            if (bestDefender.hasMovement()) friendlyUnitFinalPower /= 25 // could move anywhere, not a good place to go
            // Emphasize member of this operation
            if (army != null && bestDefender.id in army.unitIds()) friendlyUnitFinalPower *= 5
        } else dangerDivisor = 1000 // no friendly city or unit

        // Distance to center of army (if still under operational AI)
        val centerOfMass = army?.let { AiOperationLogic.centerOfMass(civ, it) }
        val distanceToCenter = centerOfMass?.aerialDistanceTo(tile) ?: Int.MAX_VALUE

        var score = when {
            // Entering a city; if part of an army moving to target, don't hide in it
            friendlyCity -> if (army != null) 1000 else friendlyCityStrength
            // Stacking with a unit
            friendlyUnitFinalPower > 0 -> friendlyUnitFinalPower
            // Moving to an empty tile
            else -> 10
        }
        if (distanceToCenter < 20 && (friendlyUnitFinalPower > 0 || friendlyCity || danger == 0)) {
            // Anywhere near center is really good, with a sliding scale further off
            score += if (distanceToCenter <= 3) 5000000 else 250000 * (20 - distanceToCenter)
        }
        if (danger > 0) score /= dangerDivisor
        return score
    }

    /** 5 if the danger is more than twice [strength], 3 if more than [strength], 2 if more than half of it, else 1 */
    private fun dangerDivisorFor(danger: Int, strength: Int) = when {
        danger > strength * 2 -> 5
        danger > strength -> 3
        danger > strength / 2 -> 2
        else -> 1
    }

    //endregion
}
