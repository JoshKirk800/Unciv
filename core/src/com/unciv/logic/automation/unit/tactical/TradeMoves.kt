package com.unciv.logic.automation.unit.tactical

/**
 *  The trade moves of CvTacticalAI: PlotPlunderTradeUnitMoves (the barbarians' PlotBarbarianPlunderTradeUnitMove is the same routine),
 *  ExecutePlunderTradeUnit and PlotPlunderTradePlotMoves.
 *
 *  A trade route is data in Unciv, not a unit: where its trader stands is [com.unciv.models.TradeRoute.getCurrentTile], what the unit does
 *  to it is [com.unciv.logic.civilization.managers.TradeRouteManager.plunder] (CvUnit::plunderTradeRoute), the same as the "Plunder trade
 *  route" action of a player.
 *
 *  Deviations: [plotParkOnTradeRouteMoves] sends the units of the tactical AI that are still free and can attack; the original loops over
 *  the units its last search for units left in m_CurrentMoveUnits, a list that is left over from whatever move ran before (and that
 *  still holds units that have been moved since), which has no meaning.
 */
object TradeMoves {

    /**
     *  CvTacticalAI::PlotPlunderTradeUnitMoves for land, then for sea: against each trader we see (a target of a trade unit), the strongest
     *  unit that can move onto its tile with movement to spare (only ships for a trader at sea) goes there and plunders it (ExecutePlunderTradeUnit),
     *  and is done for the turn.
     */
    fun plotPlunderTradeUnitMoves(ctx: TacticalContext) {
        for ((type, navalOnly) in listOf(TacticalTargetType.TradeUnitLand to false, TacticalTargetType.TradeUnitSea to true)) {
            for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
                val striker = TacticalAttacks.findUnits(ctx, target.tile, 0, navalOnly = navalOnly, mustMoveThrough = true).firstOrNull() ?: continue
                ctx.log { "plundering trade unit, ${target.tile.position}" }
                val unit = striker.unit
                unit.movement.moveToTile(target.tile)
                // CvUnit::plunderTradeRoute: the first opposing trader on the tile, the unit's moves are not used by it
                if (unit.getTile() == target.tile)
                    ctx.civ.tradeRoutes.getPlunderableRoutes(unit).firstOrNull()?.let { ctx.civ.tradeRoutes.plunder(it, unit) }
                unit.currentMovement = 0f
                ctx.unitProcessed(unit)
            }
        }
    }

    /**
     *  CvTacticalAI::PlotPlunderTradePlotMoves for land, then for sea: every free unit of the domain goes to the nearest tile an enemy trade
     *  route crosses that no unit was sent to yet, to wait for a trader to walk under it. The target is used up and the unit is done for the
     *  turn, whether it gets there or not (the original queues a move mission and ends its moves).
     */
    fun plotParkOnTradeRouteMoves(ctx: TacticalContext) {
        for ((type, water) in listOf(TacticalTargetType.TradeUnitLandPlot to false, TacticalTargetType.TradeUnitSeaPlot to true)) {
            val plots = TacticalTargets.ofType(ctx.currentTargets, type).toMutableList()
            for (unit in ctx.liveUnits()) {
                if (!TacticalAttacks.canAttack(unit) || !unit.hasMovement()) continue
                if (if (water) !unit.baseUnit.isWaterUnit else !unit.baseUnit.isLandUnit) continue
                val unitTile = unit.getTile()
                // The first of the nearest
                var chosen: TacticalTarget? = null
                var closest = Int.MAX_VALUE
                for (plot in plots) {
                    val distance = unitTile.aerialDistanceTo(plot.tile)
                    if (distance < closest) { closest = distance; chosen = plot }
                }
                val target = chosen ?: continue
                ctx.log { "${unit.name} parks on trade route, ${target.tile.position}" }
                unit.movement.tryHeadTowards(target.tile)
                unit.currentMovement = 0f
                ctx.unitProcessed(unit)
                plots.remove(target)
            }
        }
    }
}
