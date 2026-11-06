package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import java.util.PriorityQueue

/**
 * The path a trade route takes, found the way the original does (CvAStar.cpp: TradeRouteLandPathCost, TradeRouteWaterPathCost,
 * TradeRouteLandValid, TradeRouteWaterValid, TradeRouteHeuristic): an A* search on the tiles with integer costs, where a step costs 100
 * (50 along a road), and the heuristic is the distance times 100.
 * It does not depend on a unit, and borders, units and fog do not stop it.
 */
object TradeRoutePathfinder {
    private const val STEP = 100
    private const val ROAD_STEP = 50
    private const val PENALTY = 1000
    /** The original allows a route if the cost is not more than range * 100 + 99, which makes up for the + 1 or + 2 that most steps cost */
    private const val RANGE_SLACK = 99

    /** Whether trade ships can start from or reach [tile], a coastal city */
    @Readonly
    fun isOnWater(tile: Tile) = tile.neighbors.any { it.isWater }

    /**
     * @return the tiles from [origin] to [destination], both included, if their cost does not exceed [range]; `null` if there is no such path
     */
    @Readonly
    fun findPath(civ: Civilization, origin: Tile, destination: Tile, isSea: Boolean, range: Int): List<Tile>? {
        if (origin == destination) return null
        if (isSea && (!isOnWater(origin) || !isOnWater(destination))) return null

        val budget = range * STEP + RANGE_SLACK
        val allWaterPassage = civ.tech.embarkedUnitsCanEnterOcean
        val woodsAreRoads = civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)

        // Not the shortest one in every case, as in the original: the heuristic overestimates along roads
        val cameFrom = HashMap<Tile, Tile>()
        val cost = HashMap<Tile, Int>()
        val queue = PriorityQueue<Node>()
        var counter = 0
        cost[origin] = 0
        queue.add(Node(origin, STEP * origin.aerialDistanceTo(destination), counter++))

        while (queue.isNotEmpty()) {
            val (current, _, _) = queue.poll()
            if (current == destination) break
            val known = cost[current]!!
            for (next in current.neighbors) {
                val stepCost = if (isSea) getWaterStepCost(civ, current, next, allWaterPassage)
                    else getLandStepCost(current, next, woodsAreRoads)
                if (stepCost < 0) continue
                val newCost = known + stepCost
                if (newCost > budget) continue // a step never costs less than 50, so the way on cannot get cheaper
                if (newCost >= (cost[next] ?: Int.MAX_VALUE)) continue
                cost[next] = newCost
                cameFrom[next] = current
                queue.add(Node(next, newCost + STEP * next.aerialDistanceTo(destination), counter++))
            }
        }

        if (destination !in cameFrom) return null
        val path = ArrayList<Tile>()
        var tile: Tile? = destination
        while (tile != null) {
            path.add(tile)
            tile = cameFrom[tile]
        }
        path.reverse()
        return path
    }

    private class Node(val tile: Tile, val priority: Int, val order: Int) : Comparable<Node> {
        override fun compareTo(other: Node) =
            if (priority != other.priority) priority.compareTo(other.priority) else order.compareTo(other.order)
        operator fun component1() = tile
        operator fun component2() = priority
        operator fun component3() = order
    }

    @Readonly
    private fun hasRoad(tile: Tile) = tile.isCityCenter() || tile.roadStatus != RoadStatus.None

    /** @return the cost of a step on land, `-1` if it is not allowed */
    @Readonly
    private fun getLandStepCost(from: Tile, to: Tile, woodsAreRoads: Boolean): Int {
        if (!to.isLand || to.isImpassible() || to.baseTerrain == Constants.mountain)
            return -1

        if (hasRoad(from) && hasRoad(to)) return ROAD_STEP
        val isWoods = to.terrainFeatures.contains(Constants.forest) || to.terrainFeatures.contains(Constants.jungle)
        if (woodsAreRoads && isWoods) return ROAD_STEP

        var cost = STEP
        if (to.isHill() || isWoods || to.terrainFeatures.contains(Constants.ice)) cost++
        if (!to.terrainFeatures.contains("Oasis")) cost++
        return cost
    }

    /** @return the cost of a step on water, `-1` if it is not allowed. Any city can be entered */
    @Readonly
    private fun getWaterStepCost(civ: Civilization, from: Tile, to: Tile, allWaterPassage: Boolean): Int {
        if (to.isCityCenter()) return STEP
        if (!to.isWater || to.isImpassible()) return -1
        val terrain = to.getBaseTerrain()
        val isShallow = terrain.isCoast || terrain.isFreshwater
        if (!isShallow && !allWaterPassage) return -1

        var cost = STEP
        if (!(isNextToLand(from) && isNextToLand(to))) cost++
        val owner = to.getOwner()
        if (owner != null && owner.isAtWarWith(civ)) cost += PENALTY
        return cost
    }

    @Readonly
    private fun isNextToLand(tile: Tile) = tile.neighbors.any { it.isLand }
}
