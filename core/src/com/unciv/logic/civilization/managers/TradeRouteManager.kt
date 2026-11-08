package com.unciv.logic.civilization.managers

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TradeRoutePathfinder
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil

/** A city a trade unit could send a route to, and what the route would be */
class TradeDestination(val city: City, val kind: TradeRouteKind, val path: List<Tile>)

/** A connection the trade AI considers: from one of our cities, in one domain (CvTradeAI's TradeConnection) */
class TradeRouteCandidate(val origin: City, val destination: TradeDestination, val isSea: Boolean)

/**
 * The trade routes a civilization has started (CvPlayerTrade and CvGameTrade in the original).
 * Their effects on the cities are added in [com.unciv.logic.city.CityStats] from [getStatsForCity].
 */
class TradeRouteManager : IsPartOfGameInfoSerialization {

    var routes = ArrayList<TradeRoute>()

    /** The game turn each of our connections was last plundered, by [plunderKey] (CvPlayerTrade::m_aTradeConnectionWasPlundered) */
    var plunderedConnections = HashMap<String, Int>()

    @Transient
    lateinit var civ: Civilization
        private set

    /** Whether any route is possible for a kind of trade unit, valid for [possibleRoutesTurn] only */
    @Transient @Cache
    private val possibleRoutes = HashMap<Pair<Boolean, Int>, Boolean>()
    @Transient @Cache
    private var possibleRoutesTurn = -1
    @Transient @Cache
    private val potentialConnections = HashMap<String, Boolean>()
    @Transient @Cache
    private var potentialConnectionsTurn = -1

    /** The trade AI's ordered connections for [aiRoutesTurn] (CvHomelandAI::ExecuteTradeUnitMoves keeps one list per turn) */
    @Transient
    var aiPrioritizedRoutes: List<TradeRouteCandidate> = emptyList()
    @Transient
    var aiRoutesTurn = -1

    fun clone(): TradeRouteManager {
        val toReturn = TradeRouteManager()
        routes.mapTo(toReturn.routes) { it.clone() }
        toReturn.plunderedConnections.putAll(plunderedConnections)
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        for (route in routes) route.setTransients(civ)
    }

    //region Trade units and slots
    @Readonly
    fun isTradeUnit(unit: BaseUnit) = unit.hasUnique(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals)

    /** CvPlayerTrade::GetNumTradeRoutesPossible: all [UniqueType.TradeRouteSlots] together, enlarged by the percentages */
    @Readonly
    fun getNumSlots(): Int {
        var slots = 0
        var percent = 100
        civ.forEachMatchingUnique(UniqueType.TradeRouteSlots) { slots += it.params[0].toInt() }
        civ.forEachMatchingUnique(UniqueType.TradeRouteSlotsPercent) { percent += it.params[0].toInt() }
        return maxOf(slots * percent / 100, 0)
    }

    /**
     * CvPlayerTrade::GetNumTradeRoutesUsed: a slot is taken by every route, every trade unit that waits to be used, and every one being built.
     * @param ownCity if given, [ownCityQueue] is taken as its production queue
     */
    @Readonly
    fun getSlotsUsed(ownCity: City? = null, ownCityQueue: List<String>? = null): Int {
        val units = civ.gameInfo.ruleset.units
        val waiting = civ.units.getCivUnits().count { it.baseUnit.let(::isTradeUnit) }
        val inProduction = civ.cities.sumOf { city ->
            val queue = if (city == ownCity && ownCityQueue != null) ownCityQueue else city.cityConstructions.constructionQueue
            queue.count { name -> units[name]?.let(::isTradeUnit) == true }
        }
        return routes.size + waiting + inProduction
    }

    /** Whether [unit] may be built (in [city], which would produce it after [cityQueue]): there must be a free slot, and somewhere to send it */
    @Readonly
    fun canBuildTradeUnit(unit: BaseUnit, city: City, cityQueue: List<String>?): Boolean {
        if (getSlotsUsed(city, cityQueue) >= getNumSlots()) return false
        val unique = unit.getMatchingUniques(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals).firstOrNull() ?: return true
        return isAnyRoutePossible(unit.isWaterUnit, unique.params[0].toInt())
    }

    @Readonly
    private fun isAnyRoutePossible(isSea: Boolean, baseRange: Int): Boolean {
        if (possibleRoutesTurn != civ.gameInfo.turns) {
            possibleRoutes.clear()
            possibleRoutesTurn = civ.gameInfo.turns
        }
        return possibleRoutes.getOrPut(Pair(isSea, baseRange)) {
            civ.cities.any { origin -> getDestinations(origin, isSea, baseRange, onlyOne = true).isNotEmpty() }
        }
    }
    //endregion

    //region Legality and destinations
    /** CvPlayerTrade::GetTradeRouteRange */
    @Readonly
    fun getRange(origin: City, isSea: Boolean, baseRange: Int): Int {
        var extra = 0
        var percent = 100
        civ.forEachMatchingUnique(UniqueType.TradeRouteRange) {
            if (matchesDomain(it.params[1], isSea)) extra += it.params[0].toInt()
        }
        origin.forEachMatchingUnique(UniqueType.TradeRouteRangePercent) {
            if (matchesDomain(it.params[1], isSea)) percent += it.params[0].toInt()
        }
        return (baseRange + extra) * percent / 100
    }

    @Readonly
    private fun matchesDomain(filter: String, isSea: Boolean) = when (filter) {
        in Constants.all -> true
        "Land" -> !isSea
        "Water" -> isSea
        else -> false
    }

    /** The stat of the domestic trade routes [origin] can start, the building in that city decides */
    @Readonly
    private fun getDomesticKinds(origin: City): List<TradeRouteKind> {
        val kinds = ArrayList<TradeRouteKind>()
        origin.forEachMatchingUnique(UniqueType.AllowsDomesticTradeRoutes) {
            val kind = when (it.params[0]) {
                Stat.Food.name -> TradeRouteKind.Food
                Stat.Production.name -> TradeRouteKind.Production
                else -> null
            }
            if (kind != null && kind !in kinds) kinds.add(kind)
        }
        return kinds
    }

    /** CvGameTrade::CanCreateTradeRoute without the path; [ignoreExisting] keeps connections that already carry a route */
    @Readonly
    fun canConnect(origin: City, destination: City, kind: TradeRouteKind, ignoreExisting: Boolean = false): Boolean {
        if (origin == destination || origin.isBeingRazed || destination.isBeingRazed) return false
        if (!destination.getCenterTile().isExplored(civ)) return false
        val destinationCiv = destination.civ
        if (destinationCiv.isBarbarian || !destinationCiv.isAlive()) return false
        if (civ.gameInfo.league?.isTradeEmbargoed(civ, destinationCiv) == true) return false
        when (kind) {
            TradeRouteKind.International -> {
                if (destinationCiv == civ) return false
                if (!civ.knows(destinationCiv) || civ.isAtWarWith(destinationCiv)) return false
            }
            else -> {
                if (destinationCiv != civ) return false
                if (kind !in getDomesticKinds(origin)) return false
            }
        }
        // The original refuses a second route from and to the same cities, whatever it carries
        return ignoreExisting || routes.none { it.origin == origin.location && it.destination == destination.location }
    }

    /** The cities a unit that is in [origin] can start a route to, with the path. Empty if the unit is not the right one. */
    @Readonly
    fun getDestinations(origin: City, unit: BaseUnit): List<TradeDestination> {
        val unique = unit.getMatchingUniques(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals).firstOrNull()
            ?: return emptyList()
        return getDestinations(origin, unit.isWaterUnit, unique.params[0].toInt())
    }

    /**
     * The cities a unit of the given domain and base range could start a route to from [origin], with the path.
     * [ignoreExisting] includes connections that already carry one of our routes (CvTradeAI::GetAvailableTR).
     */
    @Readonly
    fun getDestinations(origin: City, isSea: Boolean, baseRange: Int, onlyOne: Boolean = false, ignoreExisting: Boolean = false): List<TradeDestination> {
        if (origin.civ != civ) return emptyList()
        val range = getRange(origin, isSea, baseRange)
        val maxDistance = (range * 100 + 99) / 50 // a step costs at least 50
        val result = ArrayList<TradeDestination>()
        val originTile = origin.getCenterTile()
        for (destination in civ.gameInfo.getCities()) {
            if (originTile.aerialDistanceTo(destination.getCenterTile()) > maxDistance) continue
            val kinds = if (destination.civ == civ) getDomesticKinds(origin) else listOf(TradeRouteKind.International)
            for (kind in kinds) {
                if (!canConnect(origin, destination, kind, ignoreExisting)) continue
                val path = TradeRoutePathfinder.findPath(civ, originTile, destination.getCenterTile(), isSea, range) ?: continue
                result.add(TradeDestination(destination, kind, path))
                if (onlyOne) return result
            }
        }
        return result
    }

    /** The trade unit of a domain this civilization would use: the first in the ruleset, as the civilization's equivalent, `null` if none */
    @Readonly
    fun getTradeUnitType(isSea: Boolean): BaseUnit? = civ.gameInfo.ruleset.units.values
        .firstOrNull { isTradeUnit(it) && it.isWaterUnit == isSea && it.uniqueTo == null }
        ?.let { civ.getEquivalentUnit(it) }

    /** The base range of [unit] as a trade unit (the original hard-codes 10 for land and 20 for sea) */
    @Readonly
    fun getBaseRange(unit: BaseUnit): Int? =
        unit.getMatchingUniques(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals).firstOrNull()?.params?.get(0)?.toInt()

    /** CvPlayerTrade::GetNumPotentialConnections(origin, domain) > 0: whether [origin] could start any route in the domain; kept for the turn */
    @Readonly
    fun hasPotentialConnection(origin: City, isSea: Boolean): Boolean {
        if (potentialConnectionsTurn != civ.gameInfo.turns) {
            potentialConnections.clear()
            potentialConnectionsTurn = civ.gameInfo.turns
        }
        return potentialConnections.getOrPut(origin.id + if (isSea) ":sea" else ":land") {
            val baseRange = getTradeUnitType(isSea)?.let { getBaseRange(it) }
            baseRange != null && getDestinations(origin, isSea, baseRange, onlyOne = true).isNotEmpty()
        }
    }

    /** CvPlayerTrade::GetNumTradeRoutesRemaining(bContinueTraining = false): free slots, trade units in production taking one */
    @Readonly
    fun getNumRoutesRemaining(): Int = getNumSlots() - getSlotsUsed()

    /** Whether the connection from [origin] to [destination] was plundered in the last 30 turns (CvPlayerTrade::CheckTradeConnectionWasPlundered) */
    @Readonly
    fun wasPlunderedRecently(origin: City, destination: City): Boolean {
        val turn = plunderedConnections[plunderKey(origin.location, destination.location)] ?: return false
        return turn + PLUNDER_MEMORY_TURNS > civ.gameInfo.turns
    }
    //endregion

    //region Establishing
    /** @return whether [unit], which must be in one of our cities, can start a route to [destination] */
    @Readonly
    fun getDestination(unit: MapUnit, destination: City, kind: TradeRouteKind): TradeDestination? {
        val origin = unit.getTile().getCity()?.takeIf { it.civ == civ } ?: return null
        return getDestinations(origin, unit.baseUnit).firstOrNull { it.city == destination && it.kind == kind }
    }

    /**
     * CvUnit::makeTradeRoute: the unit is used up and its route starts
     * @return the new route, `null` if it is not possible
     */
    fun establish(unit: MapUnit, destination: City, kind: TradeRouteKind): TradeRoute? {
        val chosen = getDestination(unit, destination, kind) ?: return null
        val unique = unit.getMatchingUniques(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals).first()
        val origin = unit.getTile().getCity()!!
        val constants = civ.gameInfo.ruleset.modOptions.constants

        val route = TradeRoute(origin.location, destination.location, kind, unit.baseUnit.isWaterUnit, unit.name,
            chosen.path.map { it.position }, unique.params[1].toInt(), constants.tradeRouteTargetTurns, civ.gameInfo.turns)
        route.setTransients(civ)
        unit.destroy()
        routes.add(route)
        for (tile in chosen.path) tile.setExplored(civ, true)

        // The trader moves right away in the original
        if (stepRoute(route)) updateAfterChange(route)
        return route
    }
    //endregion

    //region Moving
    fun startTurn() {
        // CvPlayerTrade::UpdateTradeConnectionWasPlundered
        plunderedConnections.values.removeAll { it + PLUNDER_MEMORY_TURNS <= civ.gameInfo.turns }
        for (route in routes.toList()) {
            if (stepRoute(route)) {
                if (route.circuitsCompleted >= route.circuitsToComplete) expire(route)
            }
        }
        updateYields()
    }

    /** CvGameTrade::MoveUnit: the trader goes [TradeRoute.tilesPerTurn] tiles on.
     *  @return `false` if the route was plundered on the way */
    private fun stepRoute(route: TradeRoute): Boolean {
        repeat(route.tilesPerTurn) {
            if (route.circuitsCompleted >= route.circuitsToComplete) return true
            if (route.movingForward) {
                route.pathIndex++
                if (route.pathIndex >= route.path.size - 1) {
                    route.pathIndex = route.path.size - 1
                    route.movingForward = false
                }
            } else {
                route.pathIndex--
                if (route.pathIndex <= 0) {
                    route.pathIndex = 0
                    route.movingForward = true
                    route.circuitsCompleted++
                }
            }
            val plunderer = getPlunderers(route).firstOrNull()
            if (plunderer != null) {
                plunder(route, plunderer)
                return false
            }
        }
        return true
    }

    /** The route is over: a new trade unit is waiting in the city of origin */
    private fun expire(route: TradeRoute) {
        removeRoute(route)
        val origin = route.getOriginCity() ?: return
        giveUnitBack(route, origin)
        val destination = route.getDestinationCity()
        civ.addNotification("Our trade route from [${origin.name}] to [${destination?.name}] has ended, and a new [${route.unitName}] awaits in [${origin.name}]",
            origin.location, NotificationCategory.Trade, route.unitName)
    }

    private fun giveUnitBack(route: TradeRoute, origin: City) {
        civ.units.placeUnitNearTile(origin.location, route.unitName)
    }

    /** Removes a route and refreshes what depended on it */
    private fun removeRoute(route: TradeRoute) {
        routes.remove(route)
        updateAfterChange(route)
    }

    private fun updateAfterChange(route: TradeRoute) {
        updateYields()
        route.getOriginCity()?.cityStats?.update()
        val destination = route.getDestinationCity()
        if (destination != null && destination.civ != civ) destination.cityStats.update()
    }
    //endregion

    //region Plunder
    /** Units of those at war with us that stand where the trader is: CvUnit::canPlunderTradeRoute */
    @Readonly
    fun getPlunderers(route: TradeRoute): List<MapUnit> =
        route.getCurrentTile().getUnits().filter { canPlunder(it) }.toList()

    @Readonly
    private fun canPlunder(unit: MapUnit) =
        unit.isMilitary() && !unit.isEmbarked() && unit.civ.isAtWarWith(civ)

    /** The routes [unit] could plunder where it stands. */
    @Readonly
    fun getPlunderableRoutes(unit: MapUnit): List<TradeRoute> {
        val tile = unit.getTile()
        return civ.gameInfo.civilizations.asSequence()
            .filter { it != unit.civ && it.isAlive() }
            .flatMap { it.tradeRoutes.routes }
            .filter { it.getCurrentTile() == tile && it.civ.tradeRoutes.canPlunder(unit) }
            .toList()
    }

    /** CvPlayerTrade::PlunderTradeRoute: [plunderer] gets gold, the route is over, nobody gets a trade unit back */
    fun plunder(route: TradeRoute, plunderer: MapUnit) = route.civ.tradeRoutes.plunderRoute(route, plunderer.civ, plunderer.getTile())

    private fun plunderRoute(route: TradeRoute, plunderingCiv: Civilization, tile: Tile) {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val gold = constants.tradeRoutePlunderGold * (100 + (if (route.isSea) constants.tradeRouteSeaBonusPercent else 0)) / 100
        plunderingCiv.addGold(gold)
        // CvPlayerTrade::PlunderTradeRoute: a barbarian on the route receives an extra unit with no moves left.
        if (plunderingCiv.isBarbarian && tile.getUnits().any { it.civ == plunderingCiv })
            civ.gameInfo.barbarians.spawnBarbarian(tile, ignoreNearbyLimit = true)?.currentMovement = 0f
        val origin = route.getOriginCity()?.name
        val destination = route.getDestinationCity()?.name
        removeRoute(route)
        // CvPlayerTrade::PlunderTradeRoute -> AddTradeConnectionWasPlundered on the owner
        plunderedConnections[plunderKey(route.origin, route.destination)] = civ.gameInfo.turns

        civ.addNotification("Our trade route from [$origin] to [$destination] was plundered by [${plunderingCiv.civName}]!",
            tile.position, NotificationCategory.Trade, route.unitName, NotificationIcon.War)
        if (plunderingCiv.isHuman() || plunderingCiv.isAI() && !plunderingCiv.isBarbarian)
            plunderingCiv.addNotification("We plundered a trade route of [${civ.civName}] and received [$gold] Gold!",
                tile.position, NotificationCategory.Trade, NotificationIcon.Gold)
        val destinationCiv = route.getDestinationCiv()
        if (destinationCiv != null && destinationCiv != civ && !destinationCiv.isCityState && destinationCiv != plunderingCiv)
            destinationCiv.addNotification("A trade route of [${civ.civName}] to [$destination] was plundered by [${plunderingCiv.civName}]!",
                tile.position, NotificationCategory.Trade, NotificationIcon.War)
    }
    //endregion

    //region Ending routes by events
    /** A city was destroyed or conquered: CvGameTrade::ClearAllCityTradeRoutes */
    fun clearRoutesOfCity(city: City) {
        for (other in civ.gameInfo.civilizations) {
            for (route in other.tradeRoutes.routes.toList()) {
                val isOrigin = route.origin == city.location
                if (!isOrigin && route.destination != city.location) continue
                other.tradeRoutes.removeRoute(route)
                if (isOrigin || !other.isAlive()) continue
                // Only if the city at the end is lost does the trader come back
                route.getOriginCity()?.let { other.tradeRoutes.giveUnitBack(route, it) }
                other.addNotification("Our trade route from [${route.getOriginCity()?.name}] to [${city.name}] ended, and our [${route.unitName}] returned.",
                    route.origin, NotificationCategory.Trade, route.unitName)
            }
        }
    }

    /** War: those at the place of the trader may still plunder it, the others are cancelled. CvTeam::declareWar, CvGameTrade::CancelTradeBetweenTeams */
    fun onWarDeclared(enemy: Civilization) {
        for ((owner, other) in listOf(civ to enemy, enemy to civ)) {
            for (route in owner.tradeRoutes.routes.toList()) {
                if (route.getDestinationCiv() != other) continue
                val tile = route.getCurrentTile()
                val plunderer = tile.getUnits().firstOrNull { it.isMilitary() && !it.isEmbarked() && it.civ == other }
                if (plunderer != null) {
                    owner.tradeRoutes.plunderRoute(route, other, tile)
                    continue
                }
                owner.tradeRoutes.removeRoute(route)
                owner.addNotification("Our trade route from [${route.getOriginCity()?.name}] to [${route.getDestinationCity()?.name}] was cancelled by the war with [${other.civName}]",
                    route.origin, NotificationCategory.Trade, NotificationIcon.War)
            }
        }
    }

    /** The World Congress embargo took effect: every route of ours that [shouldEnd] is removed and the trade unit goes back to its origin city.
     *  CvGameTrade::ClearAllCivTradeRoutes / ClearAllCityStateTradeRoutes (CvTradeClasses.cpp:907-973) */
    fun cancelRoutesByEmbargo(shouldEnd: (TradeRoute) -> Boolean) {
        for (route in routes.toList()) {
            if (!shouldEnd(route)) continue
            removeRoute(route)
            val origin = route.getOriginCity() ?: continue
            giveUnitBack(route, origin)
            civ.addNotification("Our trade route from [${origin.name}] to [${route.getDestinationCity()?.name}] was ended by a World Congress embargo, and our [${route.unitName}] returned.",
                origin.location, NotificationCategory.Trade, route.unitName)
        }
    }

    /** The civilization is gone */
    fun clear() {
        routes.clear()
    }
    //endregion

    //region Yields
    /** What the routes bring [city], both as origin and as destination. Added in [com.unciv.logic.city.CityStats]. */
    @Readonly
    fun getStatsForCity(city: City): Stats {
        val stats = Stats()
        for (other in civ.gameInfo.civilizations) {
            for (route in other.tradeRoutes.routes) {
                if (route.origin == city.location) stats.add(route.originYields)
                if (route.destination == city.location) stats.add(route.destinationYields)
            }
        }
        return stats
    }

    /** CvPlayerTrade::GetNumDifferentTradingPartners: the others on either end of one of our routes, or of a route to us */
    @Readonly
    fun getNumDifferentPartners(): Int {
        val partners = HashSet<Civilization>()
        for (other in civ.gameInfo.civilizations) {
            for (route in other.tradeRoutes.routes) {
                val destination = route.getDestinationCiv() ?: continue
                if (other == civ && destination != civ) partners.add(destination)
                else if (destination == civ && other != civ) partners.add(other)
            }
        }
        return partners.size
    }

    /** CvPlayerTrade::UpdateTradeConnectionValues */
    fun updateYields() {
        for (route in routes) computeYields(route)
    }

    /** A route that [unit] would start from [origin] to [destination], not kept anywhere: its yields and its duration can be looked at. */
    fun preview(unit: BaseUnit, origin: City, destination: TradeDestination): TradeRoute? {
        val unique = unit.getMatchingUniques(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals).firstOrNull() ?: return null
        val route = TradeRoute(origin.location, destination.city.location, destination.kind, unit.isWaterUnit, unit.name,
            destination.path.map { it.position }, unique.params[1].toInt(), civ.gameInfo.ruleset.modOptions.constants.tradeRouteTargetTurns, civ.gameInfo.turns)
        route.setTransients(civ)
        computeYields(route)
        return route
    }

    private fun computeYields(route: TradeRoute) {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        route.originYields = Stats()
        route.destinationYields = Stats()
        run {
            val origin = route.getOriginCity() ?: return@run
            val destination = route.getDestinationCity() ?: return@run
            val destinationCiv = destination.civ
            val bothMajor = civ.isMajorCiv && destinationCiv.isMajorCiv

            val seaPercent = if (route.isSea) constants.tradeRouteSeaBonusPercent else 0
            fun riverPercent(city: City) = if (!route.isSea && city.getCenterTile().isAdjacentToRiver()) constants.tradeRouteRiverBonusPercent else 0

            if (route.isInternational()) {
                val base = (constants.tradeRouteBaseGold * 100).toInt()

                // Gold for the origin
                var subtotal = base + getGoldTimes100(origin) / constants.tradeRouteCityGoldDivisor + getGoldTimes100(destination) / constants.tradeRouteCityGoldDivisor
                origin.forEachMatchingUnique(UniqueType.TradeRouteGold) {
                    if (route.matchesFilter(it.params[1])) subtotal += it.params[0].toInt() * 100
                }
                if (bothMajor) destination.forEachMatchingUnique(UniqueType.TradeRouteGoldForOrigin) {
                    if (route.matchesFilter(it.params[1])) subtotal += it.params[0].toInt() * 100
                }
                subtotal += getResourceGoldTimes100(origin, destination, constants.tradeRouteGoldPerDifferentResource)
                val originMultiplier = 100 + seaPercent + riverPercent(origin)
                route.originYields.gold = maxOf(base, subtotal * originMultiplier / 100) / 100f

                // Gold for the destination, a city-state has no use for it
                if (destinationCiv.isMajorCiv) {
                    var destinationSubtotal = base
                    if (bothMajor) destination.forEachMatchingUnique(UniqueType.TradeRouteGoldForDestination) {
                        if (route.matchesFilter(it.params[1])) destinationSubtotal += it.params[0].toInt() * 100
                    }
                    route.destinationYields.gold = destinationSubtotal * (100 + seaPercent + riverPercent(destination)) / 100 / 100f
                }

                // Science from what the other civilization knows that we do not, and the other way around
                if (bothMajor) {
                    route.originYields.science = getScienceFromTechDifference(civ, destinationCiv) + civ.culture.getTradeRouteScienceBonus(destinationCiv)
                    route.destinationYields.science = getScienceFromTechDifference(destinationCiv, civ)
                }
            } else {
                val startEra = civ.gameInfo.getStartingEra()
                val eraPercent = if (route.kind == TradeRouteKind.Food) startEra.growthPercent
                    else (startEra.constructPercent + startEra.trainPercent) / 2
                var percent = 100 + seaPercent
                civ.forEachMatchingUnique(UniqueType.DomesticTradeRouteYieldPercent) { percent += it.params[0].toInt() }
                val amount = ((constants.tradeRouteDomesticBaseYield + constants.tradeRouteDomesticYieldPerEra * civ.getEraNumber()) * 100).toInt() * eraPercent / 100 * percent / 100 / 100f
                if (route.kind == TradeRouteKind.Food) route.destinationYields.food = amount
                else route.destinationYields.production = amount
            }
        }
    }

    /** The gold of a city without what trade brings and without upkeep, in hundredths */
    @Readonly
    private fun getGoldTimes100(city: City): Int {
        var gold = 0f
        for ((source, stats) in city.cityStats.finalStatList)
            if (source != STAT_SOURCE && source != "Maintenance") gold += stats.gold
        return (gold * 100).toInt()
    }

    /** 50 hundredths of gold for each luxury or strategic resource that only one of the two cities has; the origin's modifiers apply */
    @Readonly
    private fun getResourceGoldTimes100(origin: City, destination: City, goldPerResource: Float): Int {
        fun City.relevantResources() = getResourcesGeneratedByCity()
            .filter { it.amount > 0 && it.resource.resourceType != ResourceType.Bonus }
            .map { it.resource.name }.toSet()
        val ours = origin.relevantResources()
        val theirs = destination.relevantResources()
        val different = (ours - theirs).size + (theirs - ours).size
        var percent = 100
        origin.forEachMatchingUnique(UniqueType.TradeRouteResourceGoldPercent) { percent += it.params[0].toInt() }
        return (goldPerResource * 100).toInt() * different * percent / 100
    }

    /** Half of what the other knows that we do not, rounded up, at least 1 if there is any */
    @Readonly
    private fun getScienceFromTechDifference(receiver: Civilization, other: Civilization): Float {
        val difference = other.tech.techsResearched.count { it !in receiver.tech.techsResearched }
        return if (difference > 0) maxOf(ceil(difference / 2.0), 1.0).toFloat() else 0f
    }
    //endregion

    companion object {
        /** The name that the effects of the routes carry in the statistics of a city */
        const val STAT_SOURCE = "Active trade routes"
        /** CvPlayerTrade::UpdateTradeConnectionWasPlundered: iTurnsUntilForget, hard-coded in the original */
        const val PLUNDER_MEMORY_TURNS = 30

        @Readonly
        fun plunderKey(origin: HexCoord, destination: HexCoord) = "${origin.x},${origin.y}>${destination.x},${destination.y}"
    }
}
