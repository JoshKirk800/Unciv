package com.unciv.models

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly

/** What a trade route brings: gold and science from other civilizations, or food or production from one's own */
enum class TradeRouteKind { International, Food, Production }

/**
 * A trade route that was established by a trade unit and that belongs to the civilization which started it
 * (CvTradeConnection in the original). The unit itself is used up and the route is only data: it walks its [path] by itself,
 * [speed][EstablishTradeRoutes] tiles per turn, to the destination and back, for [circuitsToComplete] round trips.
 *
 * Cities are referenced by their location, which does not change.
 */
class TradeRoute private constructor() : IsPartOfGameInfoSerialization {
    var origin = HexCoord()
        private set
    var destination = HexCoord()
        private set
    var kind = TradeRouteKind.International
        private set
    /** Whether a ship, not a land unit, carries the goods */
    var isSea = false
        private set
    /** The name of the unit that was used up, a new one of it appears at the origin when the route ends */
    var unitName = ""
        private set
    /** Origin to destination, both cities included */
    var path = ArrayList<HexCoord>()
        private set
    /** Where the trader is now, an index into [path] */
    var pathIndex = 0
    var movingForward = true
    var circuitsCompleted = 0
    var circuitsToComplete = 1
        private set
    var tilesPerTurn = 1
        private set
    /** The turn the route ends on, for display */
    var turnRouteComplete = 0
        private set

    /** What the city of origin gets every turn, kept up to date by [com.unciv.logic.civilization.managers.TradeRouteManager.updateYields] */
    var originYields = Stats()
    var destinationYields = Stats()

    @Transient
    lateinit var civ: Civilization
        private set

    constructor(origin: HexCoord, destination: HexCoord, kind: TradeRouteKind, isSea: Boolean, unitName: String,
                path: List<HexCoord>, tilesPerTurn: Int, targetTurns: Int, currentTurn: Int) : this() {
        this.origin = origin
        this.destination = destination
        this.kind = kind
        this.isSea = isSea
        this.unitName = unitName
        this.path = ArrayList(path)
        this.tilesPerTurn = tilesPerTurn

        // CvGameTrade::CreateTradeRoute: one round trip is there and back, the route lasts the whole number of them that is nearest to the target
        val turnsPerCircuit = (path.size - 1) * 2 / tilesPerTurn
        circuitsToComplete = if (turnsPerCircuit != 0) maxOf(targetTurns / turnsPerCircuit, 2) else 1
        turnRouteComplete = currentTurn + turnsPerCircuit * circuitsToComplete
    }

    fun clone(): TradeRoute {
        val toReturn = TradeRoute()
        toReturn.origin = origin
        toReturn.destination = destination
        toReturn.kind = kind
        toReturn.isSea = isSea
        toReturn.unitName = unitName
        toReturn.path = ArrayList(path)
        toReturn.pathIndex = pathIndex
        toReturn.movingForward = movingForward
        toReturn.circuitsCompleted = circuitsCompleted
        toReturn.circuitsToComplete = circuitsToComplete
        toReturn.tilesPerTurn = tilesPerTurn
        toReturn.turnRouteComplete = turnRouteComplete
        toReturn.originYields = originYields.clone()
        toReturn.destinationYields = destinationYields.clone()
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
    }

    @Readonly fun getOriginCity(): City? = civ.gameInfo.tileMap[origin].getCity()
    @Readonly fun getDestinationCity(): City? = civ.gameInfo.tileMap[destination].getCity()
    @Readonly fun getDestinationCiv(): Civilization? = getDestinationCity()?.civ

    /** The tile the trader is on now */
    @Readonly fun getCurrentTile(): Tile = civ.gameInfo.tileMap[path[pathIndex]]
    @Readonly fun getTiles(): List<Tile> = path.map { civ.gameInfo.tileMap[it] }

    @Readonly fun isInternational() = kind == TradeRouteKind.International

    /** Turns until the route is over, from the start of a turn */
    @Readonly fun getTurnsLeft(): Int = maxOf(turnRouteComplete - civ.gameInfo.turns, 0)

    /**
     * Matches the domain (`Land`, `Water`), the kind (`International`, `Domestic`, `Food`, `Production`),
     * or the civilization at the other end (any civ filter) - see [UniqueParameterType.TradeRouteFilter][com.unciv.models.ruleset.unique.UniqueParameterType.TradeRouteFilter]
     */
    @Readonly
    fun matchesFilter(filter: String): Boolean = when (filter) {
        in Constants.all -> true
        "Land" -> !isSea
        "Water" -> isSea
        "International" -> kind == TradeRouteKind.International
        "Domestic" -> kind != TradeRouteKind.International
        "Food" -> kind == TradeRouteKind.Food
        "Production" -> kind == TradeRouteKind.Production
        else -> getDestinationCiv()?.matchesFilter(filter, civ.state) ?: false
    }
}
