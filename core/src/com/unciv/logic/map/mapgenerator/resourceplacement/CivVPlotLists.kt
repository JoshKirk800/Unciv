package com.unciv.logic.map.mapgenerator.resourceplacement

import com.unciv.Constants
import com.unciv.logic.map.tile.Tile

/**
 *  The 15 plot lists of AssignStartingPlots.lua (GenerateLuxuryPlotListsAtCitySite and its relatives) that the luxuries, the minor strategic
 *  deposits at the city states and the bonus resources at the starts are placed on. By number: 1 coast next to land, 2 marsh, 3 flood plains,
 *  4 hills without a feature, 5 hills with jungle or forest, 6 hills with jungle, 7 hills with forest, 8 jungle on flat land, 9 forest on flat land,
 *  10 featureless desert, 11 featureless plains, 12 featureless grass without fresh water, 13 featureless grass with fresh water, 14 tundra on flat land
 *  (forests on it too), 15 forest on flat land that is not tundra. Snow hills are on none, mountains and natural wonders neither.
 */
object CivVPlotLists {
    /** The numbers 1 to 15 of the lists of the original that [tile] is on (a tile can be on several), 0 for the marble list is returned apart */
    fun listsOfTile(tile: Tile, regionLand: Set<Int>? = null): List<Int> {
        if (tile.naturalWonder != null) return emptyList()
        if (tile.isWater) {
            if (tile.baseTerrain != Constants.coast || "Atoll" in tile.terrainFeatures || Constants.ice in tile.terrainFeatures) return emptyList()
            // Next to land (the region lists: next to the landmass of the region)
            val next = tile.neighbors.filter { !it.isWater }
            val ok = if (regionLand == null) next.any() else next.any { it.getContinent() in regionLand }
            return if (ok) listOf(1) else emptyList()
        }
        if (tile.baseTerrain == Constants.mountain) return emptyList()
        val terrain = tile.baseTerrain
        val features = tile.terrainFeatures.filter { it != Constants.hill }
        if (Constants.hill in tile.terrainFeatures) {
            if (terrain == Constants.snow) return emptyList()
            return when (features.singleOrNull()) {
                null -> if (features.isEmpty()) listOf(4) else emptyList()
                Constants.jungle -> listOf(6, 5)
                Constants.forest -> listOf(7, 5)
                else -> emptyList()
            }
        }
        val fresh = tile.isAdjacentTo(Constants.freshWater)
        return when {
            features.isEmpty() -> when (terrain) {
                Constants.tundra -> listOf(14)
                Constants.desert -> listOf(10)
                Constants.plains -> listOf(11)
                Constants.grassland -> if (fresh) listOf(13) else listOf(12)
                else -> emptyList()
            }
            features.first() == "Marsh" -> listOf(2)
            features.first() == "Flood plains" -> listOf(3)
            features.first() == Constants.jungle -> listOf(8)
            features.first() == Constants.forest -> if (terrain == Constants.tundra) listOf(9, 14) else listOf(9, 15)
            else -> emptyList()
        }
    }

    fun isMarbleSite(tile: Tile): Boolean {
        if (tile.isWater || tile.naturalWonder != null || tile.baseTerrain == Constants.mountain) return false
        val features = tile.terrainFeatures.filter { it != Constants.hill }
        if (Constants.hill in tile.terrainFeatures) return features.isEmpty() && tile.baseTerrain != Constants.snow
        if (features.isNotEmpty()) return false
        return when (tile.baseTerrain) {
            Constants.tundra, Constants.desert -> true
            Constants.plains -> !tile.isAdjacentTo(Constants.freshWater)
            Constants.grassland -> !tile.isAdjacentTo(Constants.freshWater)
            else -> false
        }
    }


    /** GenerateLuxuryPlotListsAtCitySite: the lists of the tiles within [radius] of [site], not the site itself */
    fun listsAtSite(site: Tile, radius: Int): Array<List<Tile>> {
        val lists = Array(16) { ArrayList<Tile>() }
        for (ring in 1..radius) for (tile in site.getTilesAtDistance(ring)) for (n in listsOfTile(tile)) lists[n].add(tile)
        return Array(16) { lists[it] }
    }
}
