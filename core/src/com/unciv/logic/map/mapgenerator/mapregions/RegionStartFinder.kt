package com.unciv.logic.map.mapgenerator.mapregions

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.closeStartPenaltyForRing
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.firstRingFoodScores
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.firstRingProdScores
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.maximumJunk
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.minimumFoodForRing
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.minimumGoodForRing
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.minimumProdForRing
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.secondRingFoodScores
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.Companion.secondRingProdScores
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.toHexCoord
import com.unciv.models.ruleset.tile.TerrainType
import yairm210.purity.annotations.Pure
import kotlin.math.roundToInt

object RegionStartFinder {
    /** AssignStartingPlots `centerBias` and `middleBias`: the share of a region's size examined first and second */
    private const val CENTER_BIAS_PERCENT = 34
    private const val MIDDLE_BIAS_PERCENT = 67

    /** Attempts to find a good start close to the center of [region]. Calls setRegionStart with the position*/
    internal fun findStart(region: Region, tileData: TileDataMap) {
        val fallbackTiles = HashSet<Tile>()
        // Priority: 1. Adjacent to river, 2. Adjacent to coast or fresh water, 3. Other.

        // First check center rect, then middle. Only check the outer area if no good sites found
        val centerTiles = region.tileMap.getTilesInRectangle(getCentralRectangle(region.rect, CENTER_BIAS_PERCENT)).toSet()
        if (findGoodPosition(centerTiles, region, tileData, fallbackTiles)) return
        
        val middleDonut = region.tileMap.getTilesInRectangle(getCentralRectangle(region.rect, MIDDLE_BIAS_PERCENT))
            .filterNot { it in centerTiles }.toSet()
        if (findGoodPosition(middleDonut, region, tileData, fallbackTiles)) return 

        // Now check the outer tiles. For these we don't care about rivers, coasts etc
        val outerDonut = region.tileMap.getTilesInRectangle(region.rect)
            .filterNot { it in centerTiles || it in middleDonut}
        if (findEdgePosition(outerDonut, region, tileData, fallbackTiles)) return
        
        findFallbackPosition(fallbackTiles, tileData, region)
    }

    private fun findGoodPosition(centerTiles: Set<Tile>, region: Region, tileData: TileDataMap, fallbackTiles: HashSet<Tile>): Boolean {
        val riverTiles = HashSet<Tile>()
        val wetTiles = HashSet<Tile>()
        val dryTiles = HashSet<Tile>()
        for (tile in centerTiles) {
            if (tileData[tile]!!.isTwoFromCoast)
                continue // Don't even consider tiles two from coast
            if (region.continentID != -1 && region.continentID != tile.getContinent())
                continue // Wrong continent
            if (tile.isLand && !tile.isImpassible()) {
                evaluateTileForStart(tile, tileData)
                if (tile.isAdjacentToRiver())
                    riverTiles.add(tile)
                else if (tile.isAdjacentToCoast() || tile.isAdjacentTo(Constants.freshWater))
                    wetTiles.add(tile)
                else
                    dryTiles.add(tile)
            }
        }
        // Did we find a good start position?
        for (list in sequenceOf(riverTiles, wetTiles, dryTiles)) {
            if (list.any { tileData[it]!!.isGoodStart }) {
                setRegionStart(region, list
                    .filter { tileData[it]!!.isGoodStart }.maxByOrNull { tileData[it]!!.startScore }!!.position, tileData)
                return true
            }
            if (list.isNotEmpty()) // Save the best not-good-enough spots for later fallback
                fallbackTiles.add(list.maxByOrNull { tileData[it]!!.startScore }!!)
        }
        return false
    }


    private fun findEdgePosition(
        outerDonut: Sequence<Tile>,
        region: Region,
        tileData: TileDataMap,
        fallbackTiles: HashSet<Tile>
    ): Boolean {
        val dryTiles = HashSet<Tile>()
        for (tile in outerDonut) {
            if (tileData[tile]!!.isTwoFromCoast)
                continue // Don't even consider tiles two from coast, in any zone (AssignStartingPlots:FindStart)
            if (region.continentID != -1 && region.continentID != tile.getContinent())
                continue // Wrong continent
            if (tile.isLand && !tile.isImpassible()) {
                evaluateTileForStart(tile, tileData)
                dryTiles.add(tile)
            }
        }
        // Were any of them good?
        if (dryTiles.any { tileData[it]!!.isGoodStart }) {
            // Find the one closest to the center
            val center = region.rect.getCenter(Vector2())
            val closestToCenter = dryTiles.filter { tileData[it]!!.isGoodStart }
                .minByOrNull {
                (region.tileMap.getIfTileExistsOrNull(center.x.roundToInt(), center.y.roundToInt())
                    ?: region.tileMap.values.first())
                    .aerialDistanceTo(it)
            }!!

            setRegionStart(
                region,
                closestToCenter.position,
                tileData
            )
            return true
        }
        if (dryTiles.isNotEmpty())
            fallbackTiles.add(dryTiles.maxByOrNull { tileData[it]!!.startScore }!!)
        return false
    }

    private fun findFallbackPosition(
        fallbackTiles: HashSet<Tile>,
        tileData: TileDataMap,
        region: Region
    ) {
        // Fallback time. Just pick the one with best score, as long as it is above 0 (AssignStartingPlots:FindStart)
        val fallbackPosition = fallbackTiles.filter { tileData[it]!!.startScore > 0 }
            .maxByOrNull { tileData[it]!!.startScore }
        if (fallbackPosition != null) {
            setRegionStart(region, fallbackPosition.position, tileData)
            return
        }

        // Something went extremely wrong and there is somehow no place to start. Spawn some land and start there
        val panicPosition = region.rect.getPosition(Vector2()).toHexCoord()
        val panicTerrain = region.tileMap.ruleset!!.terrains.values.first { it.type == TerrainType.Land }.name
        region.tileMap[panicPosition].baseTerrain = panicTerrain
        region.tileMap[panicPosition].setTerrainFeatures(listOf())
        setRegionStart(region, panicPosition, tileData)
    }
    
    /** @returns a Rectangle centered over [originalRect] that is [biasPercent] of its size, as AssignStartingPlots:FindStart
     *  calculates its center and middle areas: the margin is half the rest rounded down, the area takes the remainder */
    @Pure
    fun getCentralRectangle(originalRect: Rectangle, biasPercent: Int): Rectangle {
        val width = originalRect.width.toInt()
        val height = originalRect.height.toInt()
        val marginX = ((width - biasPercent / 100.0 * width) / 2).toInt()
        val marginY = ((height - biasPercent / 100.0 * height) / 2).toInt()
        return Rectangle(originalRect.x + marginX, originalRect.y + marginY,
            (width - 2 * marginX).toFloat(), (height - 2 * marginY).toFloat())
    }


    /** The production that counts for the second ring: more than twice the food cannot be used without the food, so it counts as half the food (rounded up) */
    @Pure
    fun effectiveSecondRingProduction(totalFood: Int, totalProd: Int) =
        if (totalFood * 2 < totalProd) (totalFood + 1) / 2 else totalProd

    /** Evaluates a tile for starting position, setting isGoodStart and startScore in
     *  MapGenTileData. Assumes that all tiles have corresponding MapGenTileData. */
    private fun evaluateTileForStart(tile: Tile, tileData: TileDataMap) {
        val localData = tileData[tile]!!

        var totalFood = 0
        var totalProd = 0
        var totalGood = 0
        var totalJunk = 0
        var totalRivers = 0
        var totalScore = 0

        if (tile.isAdjacentToCoast()) totalScore += 40
        // The candidate's own river side, which AssignStartingPlots:EvaluateCandidatePlot (not the ring's tile) counts for the 2nd and 3rd ring
        val candidateIsRiverSide = tile.isAdjacentToRiver()

        // Go through all rings
        for (ring in 1..3) {
            // Sum up the values for this ring
            var tilesInRing = 0
            tile.forEachTileAtDistance(ring) { outerTile ->
                tilesInRing++
                val outerTileData = tileData[outerTile]!!
                if (outerTileData.isJunk)
                    totalJunk++
                else {
                    if (outerTileData.isFood) totalFood++
                    if (outerTileData.isProd) totalProd++
                    if (outerTileData.isGood) totalGood++
                    if (if (ring == 1) outerTile.isAdjacentToRiver() else candidateIsRiverSide) totalRivers++
                }
            }
            totalJunk += 6 * ring - tilesInRing // Off the map counts as junk
            // Check for minimum levels. We still keep on calculating final score in case of failure
            if (totalFood < minimumFoodForRing[ring]!!
                || totalProd < minimumProdForRing[ring]!!
                || totalGood < minimumGoodForRing[ring]!!) {
                localData.isGoodStart = false
            }

            // Ring-specific scoring
            when (ring) {
                1 -> {
                    val foodScore = firstRingFoodScores[totalFood]
                    val prodScore = firstRingProdScores[totalProd]
                    totalScore += foodScore + prodScore + totalRivers + (totalGood * 2) - (totalJunk * 3)
                }
                2 -> {
                    val foodScore = if (totalFood > 10) secondRingFoodScores.last()
                    else secondRingFoodScores[totalFood]
                    val effectiveTotalProd = effectiveSecondRingProduction(totalFood, totalProd)
                    val prodScore = if (effectiveTotalProd > 5) secondRingProdScores.last()
                    else secondRingProdScores[effectiveTotalProd]
                    totalScore += foodScore + prodScore + totalRivers+ (totalGood * 2) - (totalJunk * 3)
                }
                else -> {
                    totalScore += totalFood + totalProd + totalGood + totalRivers - (totalJunk * 2)
                }
            }
        }
        // Too much junk?
        if (totalJunk > maximumJunk) {
            localData.isGoodStart = false
        }

        // Finally check if this is near another start
        if (localData.closeStartPenalty > 0) {
            localData.isGoodStart = false
            totalScore -= Math.floorDiv(totalScore * localData.closeStartPenalty, 100)
        }
        localData.startScore = totalScore
    }

    private fun setRegionStart(region: Region, position:HexCoord, tileData: TileDataMap) {
        region.startPosition = position

        for ((ring, penalty) in closeStartPenaltyForRing) {
            region.tileMap[position].forEachTileAtDistance(ring) { outerTile ->
                tileData[outerTile]!!.addCloseStartPenalty(penalty)
            }
        }
    }
}
