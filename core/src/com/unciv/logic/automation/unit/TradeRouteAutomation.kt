package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.TradeDestination
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.Building

/** What the AI does with a trade unit: it sends it on a route from the city it is in, or goes to a city where one is possible
 *  (CvHomelandAI::ExecuteTradeUnitMoves, CvPlayerTrade::ScoreInternationalTR, ScoreFoodTR and ScoreProductionTR in a simpler form) */
object TradeRouteAutomation {

    fun automateTradeUnit(unit: MapUnit) {
        val civ = unit.civ
        val manager = civ.tradeRoutes
        val here = unit.getTile().getCity()?.takeIf { it.civ == civ }

        if (here != null) {
            val destination = chooseDestination(unit, here)
            if (destination != null && manager.establish(unit, destination.city, destination.kind) != null) return
        }

        // From this city nothing is possible, find one in reach from which it is
        val target = civ.cities.asSequence()
            .filter { it != here }
            .sortedBy { it.getCenterTile().aerialDistanceTo(unit.getTile()) }
            .take(8)
            .firstOrNull { unit.movement.canReach(it.getCenterTile()) && manager.getDestinations(it, unit.baseUnit).isNotEmpty() }
            ?: return
        unit.movement.headTowards(target.getCenterTile())
    }

    /** The original takes two international routes first, then one with food, then one with production, then international again */
    private fun chooseDestination(unit: MapUnit, origin: City): TradeDestination? {
        val manager = unit.civ.tradeRoutes
        val all = manager.getDestinations(origin, unit.baseUnit)
        val international = all.filter { it.kind == TradeRouteKind.International }
        val food = all.filter { it.kind == TradeRouteKind.Food }
        val production = all.filter { it.kind == TradeRouteKind.Production }

        val routes = manager.routes
        val order = when {
            routes.count { it.isInternational() } < 2 -> listOf(international, food, production)
            routes.none { it.kind == TradeRouteKind.Food } -> listOf(food, international, production)
            routes.none { it.kind == TradeRouteKind.Production } -> listOf(production, international, food)
            else -> listOf(international, food, production)
        }
        for (group in order) {
            val best = group.mapNotNull { destination -> score(unit, origin, destination)?.let { destination to it } }
                .maxByOrNull { it.second }?.first
            if (best != null) return best
        }
        return null
    }

    /** @return `null` if the route is not worth it */
    private fun score(unit: MapUnit, origin: City, destination: TradeDestination): Float? {
        val civ = unit.civ
        val danger = 1 + destination.path.count { tile -> tile.getOwner()?.let { it.isAtWarWith(civ) } == true } * 10

        when (destination.kind) {
            TradeRouteKind.International -> {
                val route = civ.tradeRoutes.preview(unit.baseUnit, origin, destination) ?: return null
                var difference = route.originYields.gold - route.destinationYields.gold +
                    3 * (route.originYields.science - route.destinationYields.science)
                // A city-state takes nothing from the route, so it costs us nothing
                if (destination.city.civ.isCityState) difference = maxOf(difference, 1f)
                return if (difference > 0) difference * 10 / danger else null
            }
            TradeRouteKind.Food -> {
                // Only to the smallest city, and only if that does not make us unhappier
                if (civ.getHappiness() < 0) return null
                val smallest = civ.cities.minByOrNull { it.population.population }
                if (destination.city != smallest) return null
            }
            TradeRouteKind.Production -> {
                // Only to a city that builds a wonder
                val construction = destination.city.cityConstructions.getCurrentConstruction()
                if (construction !is Building || !construction.isWonder) return null
            }
        }
        val seaFactor = if (unit.baseUnit.isWaterUnit) 2 else 1
        val score = (60 - destination.path.size) * seaFactor - danger
        return if (score > 0) score.toFloat() else null
    }
}
