package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  The naval tactical moves outside the operations: CvTacticalAI::PlotEscortEmbarkedMoves / ExecuteEscortEmbarkedMoves. A warship goes to
 *  the embarked land unit of ours that is alone on its tile, has used its moves and is within the warship's movement, the one in the most
 *  plot danger that the warship reaches this turn, to protect it.
 *
 *  Deviation: Unciv allows one military unit on a tile, so the warship takes a free water tile next to the embarked unit instead of sharing its tile.
 */
object NavalMoves {

    /** The embarked units [escort] may protect: alone on their tile, out of moves, within its movement */
    private fun embarkedUnitsToEscort(ctx: TacticalContext, escort: MapUnit): List<MapUnit> {
        val moves = escort.currentMovement.toInt()
        return ctx.civ.units.getCivUnits().filter { unit ->
            unit.baseUnit.isLandUnit && unit.isEmbarked() && unit.getTile().getUnits().count() == 1 && !unit.hasMovement() &&
                escort.getTile().aerialDistanceTo(unit.getTile()) <= moves
        }.toList()
    }

    /** The water tile next to [target] that [escort] can reach this turn and stand on, nearest first */
    private fun escortTile(escort: MapUnit, target: Tile): Tile? {
        val reachable = escort.movement.getDistanceToTiles()
        return target.neighbors.filter { it.isWater && reachable.containsKey(it) && escort.movement.canMoveTo(it) }
            .minByOrNull { reachable.getValue(it).totalMovement }
    }

    fun plotEscortEmbarkedMoves(ctx: TacticalContext) {
        val danger = ctx.danger
        for (ship in ctx.liveUnits()) {
            if (!ship.baseUnit.isWaterUnit || !ship.isMilitary() || ship.baseUnit.isAirUnit() || !ship.hasMovement()) continue
            var bestTile: Tile? = null
            var bestTarget: Tile? = null
            var highestDanger = -1
            for (embarked in embarkedUnitsToEscort(ctx, ship)) {
                val target = embarked.getTile()
                // Can this unit get to the embarked unit in one move?
                val place = escortTile(ship, target) ?: continue
                val level = danger.get(target)
                if (level > highestDanger) { highestDanger = level; bestTile = place; bestTarget = target }
            }
            if (bestTile == null) continue
            ctx.log { "${ship.name} escorts the embarked unit at ${bestTarget!!.position}" }
            TacticalMoves.moveToPlot(ctx, ship, bestTile)
        }
    }
}
