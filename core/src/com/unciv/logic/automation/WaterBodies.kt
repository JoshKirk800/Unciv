package com.unciv.logic.automation

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import java.util.WeakHashMap

/**
 *  The connected water bodies of the map (the original's water CvLandmass: every water tile, lakes included, connected through water
 *  neighbors), computed once per map and turn, and the coast tests that read their size.
 */
class WaterBodies private constructor(val turn: Int, tileCount: Int) {
    private val bodyIds = IntArray(tileCount) { -1 }
    private val sizes = ArrayList<Int>()

    /** The body of the water [tile], -1 for a land tile */
    fun bodyOf(tile: Tile): Int = bodyIds[tile.zeroBasedIndex]
    fun size(body: Int): Int = sizes[body]

    /** CvPlot::GetSizeLargestAdjacentWater: the size of the largest body of water next to [tile], 0 if none */
    fun largestAdjacentWater(tile: Tile): Int =
        tile.neighbors.filter { it.isWater }.maxOfOrNull { size(bodyOf(it)) } ?: 0

    /**
     *  CvPlot::isCoastalLand: a land [tile] next to water; with [minWaterSize] > 0 only next to a body of at least that many tiles.
     *  -1 is MIN_WATER_SIZE_FOR_OCEAN (the default of CvCity::isCoastal, which every strategy test uses but IsTestStrategy_MostlyOnTheCoast
     *  and the city specialization, with 100: not for lakes or small inland seas).
     */
    fun isCoastalLand(tile: Tile, minWaterSize: Int = -1): Boolean {
        if (tile.isWater) return false
        val minimum = if (minWaterSize == -1) tile.tileMap.gameInfo.ruleset.ai.define(AiDefine.MIN_WATER_SIZE_FOR_OCEAN) else minWaterSize
        return tile.neighbors.any { it.isWater && (minimum <= 0 || size(bodyOf(it)) >= minimum) }
    }

    companion object {
        private val cache = WeakHashMap<TileMap, WaterBodies>()

        fun of(civ: Civilization): WaterBodies {
            val turn = civ.gameInfo.turns
            synchronized(cache) { cache[civ.gameInfo.tileMap]?.let { if (it.turn == turn) return it } }
            val tiles = civ.gameInfo.tileMap.tileList
            val bodies = WaterBodies(turn, tiles.size)
            val stack = ArrayList<Tile>()
            for (start in tiles) {
                if (!start.isWater || bodies.bodyIds[start.zeroBasedIndex] >= 0) continue
                val id = bodies.sizes.size
                var size = 0
                bodies.bodyIds[start.zeroBasedIndex] = id
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val tile = stack.removeAt(stack.lastIndex)
                    size++
                    for (neighbor in tile.neighbors) {
                        if (!neighbor.isWater || bodies.bodyIds[neighbor.zeroBasedIndex] >= 0) continue
                        bodies.bodyIds[neighbor.zeroBasedIndex] = id
                        stack.add(neighbor)
                    }
                }
                bodies.sizes.add(size)
            }
            synchronized(cache) { cache[civ.gameInfo.tileMap] = bodies }
            return bodies
        }

        /** CvCity::isCoastal([minWaterSize]): the city's tile is coastal land, see [isCoastalLand] */
        fun isCoastal(city: City, minWaterSize: Int = -1): Boolean = of(city.civ).isCoastalLand(city.getCenterTile(), minWaterSize)
    }
}
