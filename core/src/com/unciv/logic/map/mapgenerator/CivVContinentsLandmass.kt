package com.unciv.logic.map.mapgenerator

import kotlin.random.Random

/** The sizes of world of Civ V (Duel to Huge) by the number of tiles of Unciv's map, to pick the values the scripts keep per world size */
enum class CivVWorldSize(val grainChange: Int, val continentsGrain: Int, val plates: Int, val atolls: Int) {
    Duel(-1, 3, 6, 2),
    Tiny(-1, 3, 9, 4),
    Small(0, 4, 12, 5),
    Standard(0, 4, 18, 7),
    Large(1, 5, 24, 9),
    Huge(1, 5, 30, 12);

    companion object {
        fun forTiles(tiles: Int) = when {
            tiles < 1500 -> Duel
            tiles < 2500 -> Tiny
            tiles < 3500 -> Small
            tiles < 5500 -> Standard
            tiles < 8000 -> Large
            else -> Huge
        }
    }
}

/**
 *  The landmass of Continents.lua (ContinentsFractalWorld:GeneratePlotTypes) on a grid of [width] x [height] cells, with FractalWorld's
 *  ShiftPlotTypes and GenerateCenterRift, and the hills and mountains it builds from two ridge fields.
 *
 *  The land is a [CivVFractal] with its edges at zero (no land at the poles), cut so [seaLevelPercent] of its cells are water, with
 *  rifts of its own in two thirds of the attempts, shifted so the map edge runs through the emptiest columns and rows, and split by a long
 *  rift that wanders up the middle (Atlantic style), until [acceptable] likes the result (the original: no landmass beyond 58 % of the land).
 *  The scripts work on a grid of hexes with the odd rows shifted; the grid is laid over Unciv's hexes by the caller, so a rift can
 *  be crossed diagonally where Unciv's neighbours differ.
 *  Not ported: the options for the age of the world (it is the normal one: hills and mountains ridges as `world_age_normal`).
 */
class CivVContinentsLandmass(
    private val width: Int,
    private val height: Int,
    private val wrapX: Boolean,
    private val rng: Random,
    private val seaLevelPercent: Int = 72
) {
    class Result(val land: Array<BooleanArray>, val hills: Array<BooleanArray>, val mountains: Array<BooleanArray>)

    private val worldSize = CivVWorldSize.forTiles(width * height)

    fun generate(acceptable: (Array<BooleanArray>) -> Boolean): Result {
        var land = Array(width) { BooleanArray(height) }
        repeat(20) {
            val grain = if (rng.nextInt(7) < 4) 2 else 1
            val riftGrain = rng.nextInt(3).let { if (it < 1) -1 else it }
            val rifts = if (riftGrain in 1..3) CivVFractal(width, height, riftGrain, false, false, rng) else null
            val fractal = CivVFractal(width, height, grain, false, false, rng, polar = true, rifts = rifts)
            val level = fractal.getHeightFromPercent(seaLevelPercent)
            land = Array(width) { x -> BooleanArray(height) { y -> fractal.getHeight(x, y) > level } }
            land = shiftToEmptiestEdge(land)
            generateCenterRift(land)
            if (acceptable(land)) return elevate(land)
        }
        return elevate(land)
    }

    /** FractalWorld:ShiftPlotTypes: the group of columns (a tenth of the width on each side of its middle) with the least land
     *  becomes the new left edge, likewise the group of rows (a fifteenth of the height) the new bottom edge */
    private fun shiftToEmptiestEdge(land: Array<BooleanArray>): Array<BooleanArray> {
        fun emptiestStart(totals: IntArray, radius: Int): Int {
            val n = totals.size
            var best = Int.MAX_VALUE
            var bestIndex = 0
            for (i in 0 until n) {
                var sum = 0
                for (d in -radius..radius) sum += totals[Math.floorMod(i + 1 + d, n).let { if (it == 0) n - 1 else it - 1 }]
                if (sum < best) { best = sum; bestIndex = i }
            }
            return bestIndex
        }
        val shiftX = emptiestStart(IntArray(width) { x -> land[x].count { it } }, width / 10)
        val shiftY = emptiestStart(IntArray(height) { y -> (0 until width).count { x -> land[x][y] } }, height / 15)
        return Array(width) { x -> BooleanArray(height) { y -> land[(x + shiftX) % width][(y + shiftY) % height] } }
    }

    private enum class Dir { NorthEast, East, West, NorthWest }

    /**
     *  FractalWorld:GenerateCenterRift. A line of sea from the bottom to the top of the map that starts an eighth of the height to one side of the
     *  middle and leans back across it, made of segments going north-east/north-west and, now and then, east (or west). The land to
     *  each side of it is then moved 3 columns and 2 rows away from it, and the gap the move leaves is sea.
     *  Like the original the rows are looked up one off (what it stored for the row it was on is read for the row below), so
     *  the arrays are indexed the same way.
     */
    private fun generateCenterRift(land: Array<BooleanArray>) {
        val leansEast = rng.nextInt(2) == 0
        val primaryMax = maxOf(1, height / 8)
        val secondaryMax = maxOf(1, height / 11)
        val tertiaryMax = maxOf(1, height / 14)
        val startDistance = if (leansEast) -(height / 8) else height / 8
        val boundary = width / 2 - startDistance
        val west = IntArray(height + 2)
        val east = IntArray(height + 2)

        fun wrap(x: Int) = Math.floorMod(x, width)
        fun setSea(x: Int, y: Int) {
            if (y in 0 until height) land[wrap(x)][y] = false
        }
        /** Map.PlotDirection for the diagonals of the rows that are shifted east when odd */
        fun diagonal(x: Int, y: Int, dir: Dir) = wrap(if (dir == Dir.NorthEast) x + (y and 1) else x - 1 + (y and 1))

        var x = width / 2 + startDistance
        var y = 0
        var direction = if (leansEast) Dir.NorthEast else Dir.NorthWest
        setSea(x, 0)
        west[1] = x - 1
        east[1] = x + 1

        fun diagonalSegment(length: Int, dir: Dir) {
            var todo = length
            while (todo > 0 && y < height - 1) {
                x = diagonal(x, y, dir)
                y++
                west[y] = x - 1
                east[y] = x + 1
                setSea(x, y)
                todo--
            }
        }

        while (y < height - 1) {
            val next: Dir
            if (leansEast) {
                when (direction) {
                    Dir.East -> {
                        val length = rng.nextInt(tertiaryMax + 1)
                        next = if (x >= boundary) Dir.NorthWest else if (rng.nextInt(3) == 1) Dir.NorthWest else Dir.NorthEast
                        repeat(length) { x++; east[y] = x + 1; setSea(x, y) }
                    }
                    Dir.NorthWest -> {
                        val length = rng.nextInt(secondaryMax + 1)
                        next = if (x >= boundary) Dir.NorthWest else if (rng.nextInt(4) == 2) Dir.East else Dir.NorthEast
                        diagonalSegment(length, Dir.NorthWest)
                    }
                    else -> {
                        val length = rng.nextInt(primaryMax + 1)
                        next = if (x >= boundary) Dir.NorthWest
                        else if (rng.nextInt(2) == 1 && y > height * 0.28) Dir.East else Dir.NorthWest
                        diagonalSegment(length, Dir.NorthEast)
                    }
                }
            } else {
                when (direction) {
                    Dir.West -> {
                        val length = rng.nextInt(tertiaryMax + 1)
                        next = if (x <= boundary) Dir.NorthEast else if (rng.nextInt(3) == 1) Dir.NorthEast else Dir.NorthWest
                        repeat(length) { x--; west[y] = x - 1; setSea(x, y) }
                    }
                    Dir.NorthEast -> {
                        val length = rng.nextInt(secondaryMax + 1)
                        next = if (x <= boundary) Dir.NorthEast else if (rng.nextInt(4) == 2) Dir.West else Dir.NorthWest
                        diagonalSegment(length, Dir.NorthEast)
                    }
                    else -> {
                        val length = rng.nextInt(primaryMax + 1)
                        next = if (x <= boundary) Dir.NorthEast
                        else if (rng.nextInt(2) == 1 && y > height * 0.28) Dir.West else Dir.NorthEast
                        diagonalSegment(length, Dir.NorthWest)
                    }
                }
            }
            direction = next
        }
        west[height] = x - 1
        east[height] = x + 1
        setSea(x, height - 1)

        val hd = 3 // horizontal drift
        val vd = 2 // vertical drift
        fun move(fromX: Int, fromY: Int, toX: Int, toY: Int) {
            if (fromX in 0 until width && toX in 0 until width && fromY in 0 until height && toY in 0 until height)
                land[toX][toY] = land[fromX][fromY]
        }
        fun clear(fromX: Int, toX: Int, y: Int) {
            if (y !in 0 until height) return
            for (cx in maxOf(0, fromX)..minOf(width - 1, toX)) land[cx][y] = false
        }

        if (leansEast) {
            for (row in height - 1 - vd downTo 0) for (cx in hd..west[row + 1]) move(cx, row, cx - hd, row + vd)
            for (row in vd until height) for (cx in east[row + 1]..width - hd - 1) move(cx, row, cx + hd, row - vd)
            for (row in 0 until vd) clear(0, west[row + 1], row)
            for (row in height - vd until height) clear(east[row + 1], width - 1, row)
            for (row in vd..height - 1 - vd) clear(west[row - vd + 1] - hd + 1, east[row + vd + 1] + hd - 1, row)
        } else {
            for (row in vd until height) for (cx in hd..west[row + 1]) move(cx, row, cx - hd, row - vd)
            for (row in height - 1 - vd downTo 0) for (cx in east[row + 1]..width - hd - 1) move(cx, row, cx + hd, row + vd)
            for (row in height - vd until height) clear(0, west[row + 1], row)
            for (row in 0 until vd) clear(east[row + 1], width - 1, row)
            for (row in vd..height - 1 - vd) clear(west[row + vd + 1] - hd + 1, east[row - vd + 1] + hd - 1, row)
        }
    }

    /** The hills and mountains of Continents.lua: two ridge fields (the world age is the normal one) */
    private fun elevate(land: Array<BooleanArray>): Result {
        val adjustment = 3
        val hillsBottom1 = 28 - adjustment
        val hillsTop1 = 28 + adjustment
        val hillsBottom2 = 72 - adjustment
        val hillsTop2 = 72 + adjustment
        val hillsNearMountains = 91 - adjustment * 2
        val mountains = 97 - adjustment

        val hillsField = CivVFractal(width, height, worldSize.continentsGrain, wrapX, false, rng)
        val mountainsField = CivVFractal(width, height, worldSize.continentsGrain, wrapX, false, rng)
        hillsField.buildRidges(rng, worldSize.plates, wrapX, 1, 2)
        mountainsField.buildRidges(rng, worldSize.plates * 2 / 3, wrapX, 6, 1)
        val bottom1 = hillsField.getHeightFromPercent(hillsBottom1)
        val top1 = hillsField.getHeightFromPercent(hillsTop1)
        val bottom2 = hillsField.getHeightFromPercent(hillsBottom2)
        val top2 = hillsField.getHeightFromPercent(hillsTop2)
        val nearMountains = mountainsField.getHeightFromPercent(hillsNearMountains)
        val mountainThreshold = mountainsField.getHeightFromPercent(mountains)
        val passThreshold = hillsField.getHeightFromPercent(hillsNearMountains)

        val hills = Array(width) { BooleanArray(height) }
        val peaks = Array(width) { BooleanArray(height) }
        for (x in 0 until width) for (y in 0 until height) {
            if (!land[x][y]) continue
            val mountainValue = mountainsField.getHeight(x, y)
            val hillValue = hillsField.getHeight(x, y)
            when {
                mountainValue >= mountainThreshold -> if (hillValue >= passThreshold) hills[x][y] = true else peaks[x][y] = true
                mountainValue >= nearMountains -> hills[x][y] = true
                hillValue in bottom1..top1 || hillValue in bottom2..top2 -> hills[x][y] = true
            }
        }
        return Result(land, hills, peaks)
    }
}
