package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TradeRouteCandidate
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType

/**
 * The trade AI: CvTradeAI (CvTradeClasses.cpp) and CvHomelandAI::ExecuteTradeUnitMoves.
 *
 * Once per turn the civilization lists every connection its cities could start (GetAvailableTR, connections that already carry a route included),
 * scores them (ScoreInternationalTR, ScoreFoodTR, ScoreProductionTR) and orders them (PrioritizeTradeRoutes); each idle trade unit then takes
 * the first entry of its domain it can start (ChooseTradeUnitTargetPlot).
 *
 * As in the shipped DLL, PrioritizeTradeRoutes sorts each kind ascending (its comparator is `a < b`), so the lowest positive score comes first;
 * the plan is to decide as the shipped game does, so this is kept.
 *
 * Inputs: plot danger [PlotDanger]; route yields [com.unciv.logic.civilization.managers.TradeRouteManager.preview]; religious pressure
 * [com.unciv.logic.city.managers.CityReligionManager.wouldExertTradeRoutePressureToward]; the wonder city [WonderProductionAi.wonderBuildCity].
 * Deviation: the change of home city (MISSION_CHANGE_TRADE_UNIT_HOME_CITY) is a walk to the origin city, Unciv has no such mission.
 */
object TradeRouteAutomation {

    /** CvHomelandAI::ExecuteTradeUnitMoves for one unit, with CvTradeAI::ChooseTradeUnitTargetPlot */
    fun automateTradeUnit(unit: MapUnit): Unit = timeThis("AI.TradeRoutes") {
        val civ = unit.civ
        val manager = civ.tradeRoutes
        val candidates = getPrioritizedRoutes(civ)
        val slots = manager.getNumSlots()
        val duplicates = countDuplicates(candidates, slots)
        val isSea = unit.baseUnit.isWaterUnit
        val here = unit.getTile().getCity()?.takeIf { it.civ == civ }

        for ((index, candidate) in candidates.withIndex()) {
            if (index >= slots + duplicates) {
                val tradeUnits = manager.routes.size + civ.units.getCivUnits().count { manager.isTradeUnit(it.baseUnit) }
                if (slots - tradeUnits == 0) {
                    // Wrong domain for every free slot: the unit is disbanded
                    unit.disband()
                    return@timeThis
                }
            }
            if (candidate.isSea != isSea) continue
            val destination = candidate.destination
            if (!manager.canConnect(candidate.origin, destination.city, destination.kind)) continue
            if (candidate.origin == here) {
                manager.establish(unit, destination.city, destination.kind)
            } else if (unit.movement.canReach(candidate.origin.getCenterTile())) {
                unit.movement.tryHeadTowards(candidate.origin.getCenterTile())
            }
            return@timeThis
        }
    }

    /** ChooseTradeUnitTargetPlot: entries among the first [slots] with the same origin, destination and kind */
    private fun countDuplicates(candidates: List<TradeRouteCandidate>, slots: Int): Int {
        val end = minOf(slots, candidates.size)
        var duplicates = 0
        for (i in 0 until end) for (j in i + 1 until end) {
            val a = candidates[i]
            val b = candidates[j]
            if (a.origin == b.origin && a.destination.city == b.destination.city && a.destination.kind == b.destination.kind) duplicates++
        }
        return duplicates
    }

    /** CvTradeAI::PrioritizeTradeRoutes, kept for the turn */
    fun getPrioritizedRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val manager = civ.tradeRoutes
        if (manager.aiRoutesTurn != civ.gameInfo.turns) {
            manager.aiPrioritizedRoutes = timeThis("AI.PrioritizeTradeRoutes") { prioritizeTradeRoutes(civ) }
            manager.aiRoutesTurn = civ.gameInfo.turns
        }
        return manager.aiPrioritizedRoutes
    }

    /** CvTradeAI::PrioritizeTradeRoutes: international #1 and #2, the first food, the first production, the other internationals */
    fun prioritizeTradeRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val available = getAvailableRoutes(civ)
        if (available.isEmpty()) return available
        val danger = PlotDanger(civ)

        var food: List<TradeRouteCandidate> = emptyList()
        if (civ.getHappiness() >= 0) {
            val smallest = smallestCity(civ)
            if (smallest != null) food = sortAscending(available) { scoreFood(civ, it, smallest, danger) }
        }

        val productionTargets = productionTargetCities(civ)
        val production = if (productionTargets.isEmpty()) emptyList()
            else sortAscending(available) { scoreProduction(civ, it, productionTargets, danger) }

        val gold = sortAscending(available) { scoreInternational(civ, it, danger) }

        val result = ArrayList<TradeRouteCandidate>()
        result.addAll(gold.take(INTERNATIONAL_ROUTES_FIRST))
        food.firstOrNull()?.let { result.add(it) }
        production.firstOrNull()?.let { result.add(it) }
        result.addAll(gold.drop(INTERNATIONAL_ROUTES_FIRST))
        return result
    }

    /** The positive-scoring candidates, stable-sorted with the shipped `a < b` comparator */
    private inline fun sortAscending(candidates: List<TradeRouteCandidate>, score: (TradeRouteCandidate) -> Int): List<TradeRouteCandidate> =
        candidates.map { it to score(it) }.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }

    /** CvTradeAI::GetAvailableTR: from each of our cities to every city, by destination, land before sea, with a valid path */
    fun getAvailableRoutes(civ: Civilization): List<TradeRouteCandidate> {
        val manager = civ.tradeRoutes
        val domains = listOf(false, true).mapNotNull { isSea ->
            manager.getTradeUnitType(isSea)?.let { manager.getBaseRange(it) }?.let { isSea to it }
        }
        val result = ArrayList<TradeRouteCandidate>()
        for (origin in civ.cities) {
            val byDomain = domains.map { (isSea, range) -> isSea to manager.getDestinations(origin, isSea, range, ignoreExisting = true) }
            for (destinationCity in civ.gameInfo.getCities()) {
                for ((isSea, destinations) in byDomain)
                    for (destination in destinations)
                        if (destination.city == destinationCity) result.add(TradeRouteCandidate(origin, destination, isSea))
            }
        }
        return result
    }

    /** The first city with the fewest citizens */
    private fun smallestCity(civ: Civilization): City? {
        var smallest: City? = null
        for (city in civ.cities) if (smallest == null || city.population.population < smallest.population.population) smallest = city
        return smallest
    }

    /** The wonder city of the city specialization AI and every city building a spaceship part */
    private fun productionTargetCities(civ: Civilization): List<City> {
        val targets = ArrayList<City>()
        WonderProductionAi.wonderBuildCity(civ)?.let { targets.add(it) }
        for (city in civ.cities)
            if ((city.cityConstructions.getCurrentConstruction() as? INonPerpetualConstruction)?.hasUnique(UniqueType.SpaceshipPart) == true)
                targets.add(city)
        return targets
    }

    //region Scores
    /**
     * CvTradeAI::ScoreInternationalTR: (gold delta x100 + 3 x tech delta + 2 x religion delta) x 10 / danger, doubled towards a major
     * civilization we have no route with yet if we have tourism; 0 if the connection was plundered in the last 30 turns.
     */
    fun scoreInternational(civ: Civilization, candidate: TradeRouteCandidate, danger: PlotDanger): Int {
        val destination = candidate.destination
        if (destination.kind != TradeRouteKind.International) return 0
        val manager = civ.tradeRoutes
        if (manager.wasPlunderedRecently(candidate.origin, destination.city)) return 0
        val partner = destination.city.civ
        val dangerSum = dangerSum(civ, destination.path, danger)

        val unitType = manager.getTradeUnitType(candidate.isSea) ?: return 0
        val route = manager.preview(unitType, candidate.origin, destination) ?: return 0
        var goldDelta = (route.originYields.gold * 100).toInt() - (route.destinationYields.gold * 100).toInt()
        // Out of a logjam: a city-state route is worth trying even if it gives us less than nothing
        if (goldDelta <= 0 && partner.isCityState) goldDelta = 1

        val techDelta = techDifference(civ, partner) - techDifference(partner, civ)
        val religionDelta = religionDelta(civ, candidate.origin, destination.city)

        var score = (goldDelta + techDelta * 3 + religionDelta * 2) * 10 / dangerSum
        if (civ.ai.tourism() > 0 && !partner.isCityState && !isConnected(civ, partner)) score *= 2
        return score
    }

    /** CvTradeAI::ScoreFoodTR: (60 - path length, doubled at sea) - danger, only to the smallest city */
    fun scoreFood(civ: Civilization, candidate: TradeRouteCandidate, smallest: City, danger: PlotDanger): Int {
        val destination = candidate.destination
        if (destination.kind != TradeRouteKind.Food) return 0
        if (civ.tradeRoutes.wasPlunderedRecently(candidate.origin, destination.city)) return 0
        // The shipped test only rejects a destination that differs from the smallest city in both column and row
        val to = destination.city.location
        val small = smallest.location
        if (HexMath.getColumn(to) != HexMath.getColumn(small) && HexMath.getRow(to) != HexMath.getRow(small)) return 0
        return distanceScore(candidate) - dangerSum(civ, destination.path, danger)
    }

    /** CvTradeAI::ScoreProductionTR: as [scoreFood], to the wonder city or a city building a spaceship part */
    fun scoreProduction(civ: Civilization, candidate: TradeRouteCandidate, targets: List<City>, danger: PlotDanger): Int {
        val destination = candidate.destination
        if (destination.kind != TradeRouteKind.Production) return 0
        if (civ.tradeRoutes.wasPlunderedRecently(candidate.origin, destination.city)) return 0
        if (destination.city !in targets) return 0
        return distanceScore(candidate) - dangerSum(civ, destination.path, danger)
    }

    private fun distanceScore(candidate: TradeRouteCandidate): Int {
        val score = MAX_DISTANCE - candidate.destination.path.size
        return if (candidate.isSea) score * 2 else score
    }

    /** 1 + the danger of each tile of the path; a tile without danger counts 1 if we cannot see it and 1000 if it is of someone at war with us */
    fun dangerSum(civ: Civilization, path: List<Tile>, danger: PlotDanger): Int {
        var sum = 1
        for (tile in path) {
            var value = danger.get(tile)
            if (value == 0) {
                if (!tile.isVisible(civ)) value += 1
                val owner = tile.getOwner()
                if (owner != null && civ.isAtWarWith(owner)) value += 1000
            }
            sum += value
        }
        return sum
    }

    /** CvGameTrade::GetTechDifference: the technologies [other] knows that [civ] does not; 0 with a city-state */
    fun techDifference(civ: Civilization, other: Civilization): Int {
        if (!civ.isMajorCiv || !other.isMajorCiv || civ == other) return 0
        return other.tech.techsResearched.count { it !in civ.tech.techsResearched }
    }

    /**
     * The religion term of CvTradeAI::ScoreInternationalTR, only with a religion we founded: the pressure of our religion the route would carry
     * to them, minus that of another religion it would carry back, when the two religions differ.
     */
    private fun religionDelta(civ: Civilization, origin: City, destination: City): Int {
        val ownReligion = civ.religionManager.religion?.takeIf { it.isMajorReligion() && it.foundingCiv == civ }?.name ?: return 0
        val toward = origin.religion.wouldExertTradeRoutePressureToward(destination)
        val back = destination.religion.wouldExertTradeRoutePressureToward(origin)
        if (toward?.first == back?.first) return 0
        var delta = 0
        if (toward != null && toward.first == ownReligion) delta += toward.second
        if (back != null && back.first != ownReligion) delta -= back.second
        return delta
    }

    /** CvGameTrade::IsPlayerConnectedToPlayer: a route from one to the other, either way */
    private fun isConnected(civ: Civilization, other: Civilization): Boolean =
        civ.tradeRoutes.routes.any { it.getDestinationCiv() == other } || other.tradeRoutes.routes.any { it.getDestinationCiv() == civ }
    //endregion

    /** PrioritizeTradeRoutes: uiNumInternationalTradesToAdd */
    private const val INTERNATIONAL_ROUTES_FIRST = 2
    /** ScoreFoodTR / ScoreProductionTR: iMaxDistance */
    private const val MAX_DISTANCE = 60
}
