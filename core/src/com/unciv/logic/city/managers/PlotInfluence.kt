package com.unciv.logic.city.managers

import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import java.util.PriorityQueue

/**
 *  The influence path of the original: the cost of reaching a tile from a city by the path of least step costs, which decides what
 *  the tile costs in gold and which tile the border takes next (CvMap::calculateInfluenceDistance, CvAStar.cpp InfluenceCost / InfluenceValid).
 *
 *  The original runs an A* with the hex distance as heuristic. Every step costs at least 1, so that heuristic never overestimates and the
 *  search finds the cheapest path; this class searches the same graph with Dijkstra's algorithm and gets the same cost.
 *  (The original also lets the finder keep its closed list between calls from the same city, `bCorrectButSlower` false; this is not
 *  reproduced, a repeated call from the same city costs the same as a first one here.)
 */
object PlotInfluence {
    /** InfluenceCost: the extra cost of a step into a tile that another civilization owns */
    private const val FOREIGN_STEP_COST = 15
    private const val MIN_STEP_COST = 1
    private const val MAX_STEP_COST = 3

    /**
     *  What entering [tile] adds (before the 1..3 clamp): for a mountain (a base terrain with the unique `Occurs in chains`) its terrain cost
     *  only (INFLUENCE_MOUNTAIN_COST), else the sum of the costs of its base terrain, natural wonder and features (hill: INFLUENCE_HILL_COST;
     *  CvTerrainInfo::getInfluenceCost, CvFeatureInfo::getInfluenceCost).
     */
    @Readonly
    fun terrainCost(tile: Tile): Int {
        val base = tile.getBaseTerrain()
        if (base.isMountain) return base.influenceCost
        return tile.allTerrains.sumOf { it.influenceCost }
    }

    /**
     *  CvAStar.cpp InfluenceCost: the step from [from] to the neighbor [to] on a path that starts at [source]. The first step out of the
     *  source costs 1 (unless USE_FIRST_RING_INFLUENCE_TERRAIN_COST); later steps cost the river crossed (INFLUENCE_RIVER_COST) plus the terrain
     *  cost of [to], held within 1..3. Entering a tile that belongs to another civilization than the source's, +15 - not on the first step.
     */
    @Readonly
    fun stepCost(from: Tile, to: Tile, source: Tile): Int {
        val ai = source.ruleset.ai
        if (from != source || ai.define(AiDefine.USE_FIRST_RING_INFLUENCE_TERRAIN_COST) != 0) {
            var cost = 0
            if (from.isConnectedByRiver(to)) cost += ai.define(AiDefine.INFLUENCE_RIVER_COST)
            cost += terrainCost(to)
            cost = cost.coerceIn(MIN_STEP_COST, MAX_STEP_COST)
            val sourceOwner = source.getOwner()
            val toOwner = to.getOwner()
            if (toOwner != null && sourceOwner != null && toOwner != sourceOwner) cost += FOREIGN_STEP_COST
            return cost
        }
        return MIN_STEP_COST
    }

    /**
     *  CvMap::calculateInfluenceDistance for several destinations at once: the cost of the cheapest path from [source] to each of [targets]
     *  that lies at most [maxRange] tiles away. A target that is farther away or cannot be reached is missing from the result
     *  (the original returns -1).
     */
    @Readonly
    fun distances(source: Tile, targets: Collection<Tile>, maxRange: Int): Map<Tile, Int> {
        @LocalState val wanted = HashSet<Tile>()
        for (target in targets) if (target.aerialDistanceTo(source) <= maxRange) wanted.add(target)
        @LocalState val result = HashMap<Tile, Int>()
        if (wanted.isEmpty()) return result

        @LocalState val best = HashMap<Tile, Int>()
        best[source] = 0
        @LocalState val queue = PriorityQueue<Pair<Tile, Int>>(compareBy { it.second })
        queue.add(source to 0)
        while (queue.isNotEmpty() && wanted.isNotEmpty()) {
            val (tile, cost) = queue.poll()
            if (cost > best.getValue(tile)) continue  // a cheaper way to this tile was found after this entry was queued
            if (wanted.remove(tile)) result[tile] = cost
            for (neighbor in tile.neighbors) {
                val newCost = cost + stepCost(tile, neighbor, source)
                if (newCost >= (best[neighbor] ?: Int.MAX_VALUE)) continue
                best[neighbor] = newCost
                queue.add(neighbor to newCost)
            }
        }
        return result
    }

    /** CvMap::calculateInfluenceDistance: the cost of the cheapest path from [source] to [dest], -1 if [dest] is farther than [maxRange] or unreachable */
    @Readonly
    fun distance(source: Tile, dest: Tile, maxRange: Int): Int = distances(source, listOf(dest), maxRange)[dest] ?: -1
}
