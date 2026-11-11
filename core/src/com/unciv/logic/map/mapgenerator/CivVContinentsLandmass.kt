package com.unciv.logic.map.mapgenerator

import kotlin.random.Random

/** The sizes of world of Civ V (Duel to Huge) by the number of tiles of Unciv's map, to pick the values the scripts keep per world size */
enum class CivVWorldSize(val grainChange: Int, val continentsGrain: Int, val plates: Int, val atolls: Int, val naturalWonders: Int) {
    Duel(-1, 3, 6, 2, 2),
    Tiny(-1, 3, 9, 4, 3),
    Small(0, 4, 12, 5, 4),
    Standard(0, 4, 18, 7, 5),
    Large(1, 5, 24, 9, 6),
    Huge(1, 5, 30, 12, 7);

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
 *  The hex grid the landmass is laid on: [width] x [height] cells, which cells touch, the distance in steps between two, where a cell's
 *  centre is (the unit being the distance between the centres of two neighbours) and the six unit vectors towards a cell's neighbours.
 *  The scripts' grid has pointy hexes (a row of cells is a straight line, the odd rows shifted east); Unciv's hexes are flat (a column
 *  is a straight line, the odd columns shifted up): the rift and the ridges are made on whichever grid the caller gives, so they follow
 *  the real adjacency of the map.
 */
interface HexGeometry {
    val width: Int
    val height: Int
    fun neighbors(x: Int, y: Int): List<Pair<Int, Int>>
    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int
    fun center(x: Int, y: Int): Pair<Double, Double>
    val directions: List<Pair<Double, Double>>
}

/** The scripts' own grid: pointy hexes, rows shifted east when odd, wrapping in x when [wrapX] */
class PointyHexGeometry(override val width: Int, override val height: Int, private val wrapX: Boolean) : HexGeometry {
    private fun wrap(x: Int) = if (wrapX) Math.floorMod(x, width) else x

    override fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> {
        val shift = y and 1
        val candidates = listOf(x + 1 to y, x - 1 to y, x + shift to y + 1, x - 1 + shift to y + 1, x + shift to y - 1, x - 1 + shift to y - 1)
        return candidates.map { wrap(it.first) to it.second }.filter { it.first in 0 until width && it.second in 0 until height }
    }

    override fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        // axial coordinates of the offset grid
        fun axial(x: Int, y: Int) = (x - (y - (y and 1)) / 2) to y
        var best = Int.MAX_VALUE
        for (shift in (if (wrapX) listOf(-width, 0, width) else listOf(0))) {
            val (q1, r1) = axial(x1, y1)
            val (q2, r2) = axial(x2 + shift, y2)
            val dq = q1 - q2
            val dr = r1 - r2
            best = minOf(best, (kotlin.math.abs(dq) + kotlin.math.abs(dr) + kotlin.math.abs(dq + dr)) / 2)
        }
        return best
    }

    override fun center(x: Int, y: Int) = (x + 0.5 * (y and 1)) to (y * 0.866025)

    override val directions = listOf(0.5 to 0.866025, 1.0 to 0.0, 0.5 to -0.866025, -0.5 to -0.866025, -1.0 to 0.0, -0.5 to 0.866025)
}

/**
 *  The landmass of Continents.lua (ContinentsFractalWorld:GeneratePlotTypes) on a grid of [width] x [height] cells, with FractalWorld's
 *  ShiftPlotTypes and GenerateCenterRift, and the hills and mountains it builds from two ridge fields.
 *
 *  The land is a [CivVFractal] with its edges at zero (no land at the poles), cut so [seaLevelPercent] of its cells are water, with
 *  rifts of its own in two thirds of the attempts, shifted so the map edge runs through the emptiest columns and rows, and split by a long
 *  rift that wanders up the middle (Atlantic style), until [acceptable] likes the result (the original: no landmass beyond 58 % of the land).
 *  The centre rift is a line the scripts draw on their own grid and that is followed here over the cells of [geometry] from step to step
 *  (so no land touches across it), and the ridges are Voronoi diagrams in the steps of [geometry].
 *  Not ported: the options for the age of the world (it is the normal one: hills and mountains ridges as `world_age_normal`).
 */
class CivVContinentsLandmass(
    private val geometry: HexGeometry,
    private val wrapX: Boolean,
    private val rng: Random,
    private val seaLevelPercent: Int = 72,
    /** In billions of years: 3 (young: more hills, more and sharper mountains, more ridges), 4 (normal), 5 (old: fewer, worn down) */
    private val worldAge: Int = 4
) {
    private val width = geometry.width
    private val height = geometry.height

    /** The cells of the centre rift of the attempt that was returned, from the bottom to the top */
    var centreRift: List<Pair<Int, Int>> = emptyList()
        private set

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
     *  middle and leans back across it, made of segments going north-east/north-west and, now and then, east (or west); the original's
     *  choices (lengths, turns) on the scripts' pointy grid, turned into the line of cells of [geometry] that stays closest to it, one
     *  neighbour at a time. The land to each side of it is then moved 3 columns and 2 rows away from it, and the gap the move leaves is sea.
     */
    private fun generateCenterRift(land: Array<BooleanArray>) {
        val leansEast = rng.nextInt(2) == 0
        val primaryMax = maxOf(1, height / 8)
        val secondaryMax = maxOf(1, height / 11)
        val tertiaryMax = maxOf(1, height / 14)
        val startDistance = if (leansEast) -(height / 8) else height / 8
        val boundary = width / 2 - startDistance

        fun wrap(x: Int) = Math.floorMod(x, width)
        /** Map.PlotDirection for the diagonals of the rows that are shifted east when odd */
        fun diagonal(x: Int, y: Int, dir: Dir) = wrap(if (dir == Dir.NorthEast) x + (y and 1) else x - 1 + (y and 1))

        // The line on the scripts' grid, as the original walks it
        val line = ArrayList<Pair<Int, Int>>()
        var x = width / 2 + startDistance
        var y = 0
        var direction = if (leansEast) Dir.NorthEast else Dir.NorthWest
        line.add(x to 0)

        fun diagonalSegment(length: Int, dir: Dir) {
            var todo = length
            while (todo > 0 && y < height - 1) {
                x = diagonal(x, y, dir)
                y++
                line.add(x to y)
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
                        repeat(length) { x++; line.add(x to y) }
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
                        repeat(length) { x--; line.add(x to y) }
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

        // The cells of the geometry along that line
        val cells = followLine(line)
        centreRift = cells
        val west = IntArray(height) { -1 }
        val east = IntArray(height) { -1 }
        val minX = IntArray(height) { Int.MAX_VALUE }
        val maxX = IntArray(height) { Int.MIN_VALUE }
        for ((cx, cy) in cells) {
            land[cx][cy] = false
            minX[cy] = minOf(minX[cy], cx)
            maxX[cy] = maxOf(maxX[cy], cx)
        }
        // A row the line does not cross (the cells step up by one at most, but a row can still be skipped on a coarse grid) takes the row below
        var previousWest = line.first().first - 1
        var previousEast = line.first().first + 1
        for (row in 0 until height) {
            if (minX[row] != Int.MAX_VALUE) { previousWest = minX[row] - 1; previousEast = maxX[row] + 1 }
            west[row] = previousWest
            east[row] = previousEast
        }

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
            for (row in height - 1 - vd downTo 0) for (cx in hd..west[row]) move(cx, row, cx - hd, row + vd)
            for (row in vd until height) for (cx in east[row]..width - hd - 1) move(cx, row, cx + hd, row - vd)
            for (row in 0 until vd) clear(0, west[row], row)
            for (row in height - vd until height) clear(east[row], width - 1, row)
            for (row in vd..height - 1 - vd) clear(west[row - vd] - hd + 1, east[row + vd] + hd - 1, row)
        } else {
            for (row in vd until height) for (cx in hd..west[row]) move(cx, row, cx - hd, row - vd)
            for (row in height - 1 - vd downTo 0) for (cx in east[row]..width - hd - 1) move(cx, row, cx + hd, row + vd)
            for (row in height - vd until height) clear(0, west[row], row)
            for (row in 0 until vd) clear(east[row], width - 1, row)
            for (row in vd..height - 1 - vd) clear(west[row + vd] - hd + 1, east[row - vd] + hd - 1, row)
        }
    }

    /**
     *  The cells of [geometry] that a line of the scripts' grid runs through: the line is scaled to the map's size, and from the cell at
     *  its start the walk goes to the neighbour nearest the next point of the line, until the end, so every cell is next to the one
     *  before it and the line cannot be crossed.
     */
    private fun followLine(line: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val centres = (0 until width).flatMap { x -> (0 until height).map { y -> geometry.center(x, y) } }
        val targetWidth = centres.maxOf { it.first }
        val targetHeight = centres.maxOf { it.second }
        // The same shares of the width and of the height as on the scripts' grid
        val points = line.map { (x, y) ->
            (x.toDouble() + 0.5 * (y and 1)) / width * targetWidth to (y.toDouble() / (height - 1)) * targetHeight
        }
        fun cellNear(px: Double, py: Double): Pair<Int, Int> {
            var best = 0 to 0
            var bestDistance = Double.MAX_VALUE
            for (x in 0 until width) {
                val cx = geometry.center(x, 0).first
                if (kotlin.math.abs(cx - px) > bestDistance) continue
                for (y in 0 until height) {
                    val (ccx, ccy) = geometry.center(x, y)
                    val d = (ccx - px) * (ccx - px) + (ccy - py) * (ccy - py)
                    if (d < bestDistance) { bestDistance = d; best = x to y }
                }
            }
            return best
        }

        val result = ArrayList<Pair<Int, Int>>()
        var current = cellNear(points.first().first, points.first().second).let { it.first to 0 }
        result.add(current)
        for (point in points.drop(1)) {
            var guard = 0
            while (guard++ < 4 * width) {
                val (cx, cy) = geometry.center(current.first, current.second)
                val here = (cx - point.first) * (cx - point.first) + (cy - point.second) * (cy - point.second)
                if (here <= 0.35) break // within half a step
                val next = geometry.neighbors(current.first, current.second).minByOrNull { (nx, ny) ->
                    val (mx, my) = geometry.center(nx, ny)
                    (mx - point.first) * (mx - point.first) + (my - point.second) * (my - point.second)
                } ?: break
                val (mx, my) = geometry.center(next.first, next.second)
                if ((mx - point.first) * (mx - point.first) + (my - point.second) * (my - point.second) >= here) break // no neighbour is nearer
                current = next
                result.add(current)
            }
        }
        return result
    }

    /** The hills and mountains of Continents.lua: two ridge fields (the world age is the normal one) */
    private fun elevate(land: Array<BooleanArray>): Result {
        val adjustment = when { worldAge <= 3 -> 5; worldAge == 4 -> 3; else -> 2 }
        val numPlates = worldSize.plates * when { worldAge <= 3 -> 1.5f; worldAge == 4 -> 1f; else -> 0.75f }
        val hillsBottom1 = 28 - adjustment
        val hillsTop1 = 28 + adjustment
        val hillsBottom2 = 72 - adjustment
        val hillsTop2 = 72 + adjustment
        val hillsNearMountains = 91 - adjustment * 2
        val mountains = 97 - adjustment

        val hillsBase = CivVFractal(width, height, worldSize.continentsGrain, wrapX, false, rng)
        val mountainsBase = CivVFractal(width, height, worldSize.continentsGrain, wrapX, false, rng)
        val hillsField = ridgeField(hillsBase, numPlates.toInt(), wrapX, 1, 2)
        val mountainsField = ridgeField(mountainsBase, (numPlates * 2 / 3).toInt(), wrapX, 6, 1)
        val bottom1 = percent(hillsField, hillsBottom1)
        val top1 = percent(hillsField, hillsTop1)
        val bottom2 = percent(hillsField, hillsBottom2)
        val top2 = percent(hillsField, hillsTop2)
        val nearMountains = percent(mountainsField, hillsNearMountains)
        val mountainThreshold = percent(mountainsField, mountains)
        val passThreshold = percent(hillsField, hillsNearMountains)

        val hills = Array(width) { BooleanArray(height) }
        val peaks = Array(width) { BooleanArray(height) }
        for (x in 0 until width) for (y in 0 until height) {
            if (!land[x][y]) continue
            val mountainValue = mountainsField[x][y]
            val hillValue = hillsField[x][y]
            when {
                mountainValue >= mountainThreshold -> if (hillValue >= passThreshold) hills[x][y] = true else peaks[x][y] = true
                mountainValue >= nearMountains -> hills[x][y] = true
                hillValue in bottom1..top1 || hillValue in bottom2..top2 -> hills[x][y] = true
            }
        }
        return Result(land, hills, peaks)
    }

    /** CvFractal::getHeightFromPercent on a field of the map's cells */
    private fun percent(field: Array<IntArray>, percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        val total = width * height
        var lower = 0
        var upper = 255
        var estimate = 255 * p / 100
        while (estimate != lower) {
            var below = 0
            for (column in field) for (v in column) if (v < estimate) below++
            if (100 * below / total > p) { upper = estimate; estimate = (upper + lower) / 2 }
            else { lower = estimate; estimate = (upper + lower) / 2 }
        }
        return estimate
    }

    /**
     *  CvFractal::ridgeBuilder on the cells of [geometry]: [numSeeds] (at least 3) random cells, each with a weakness, a favoured direction
     *  and its strength; a cell's value is how near its closest seed is compared to the second closest (255 x closest / second closest: low
     *  in the middle of a region, high on the borders between regions: ridges), blended with [base] as
     *  (ridge x [blendRidge] + base x [blendFract]) / (sum). [biased] adds the seeds' weakness, a little noise and the direction bias to the distance.
     */
    private fun ridgeField(base: CivVFractal, numSeeds: Int, biased: Boolean, blendRidge: Int, blendFract: Int): Array<IntArray> {
        class Seed(val x: Int, val y: Int, val weakness: Int, val direction: Int, val strength: Int)
        val seeds = ArrayList<Seed>()
        repeat(maxOf(numSeeds, 3)) {
            var x = rng.nextInt(width)
            var y = rng.nextInt(height)
            val weakness = maxOf(0, rng.nextInt(7) - 3)
            val direction = rng.nextInt(6)
            val strength = maxOf(0, rng.nextInt(8) - 4)
            var tries = 0
            while (seeds.any { geometry.distance(x, y, it.x, it.y) < 7 } && tries++ < 200) { x = rng.nextInt(width); y = rng.nextInt(height) }
            seeds.add(Seed(x, y, weakness, direction, strength))
        }
        fun estimateDirection(dx: Double, dy: Double): Int {
            var best = 0.0
            var index = -1
            for ((i, d) in geometry.directions.withIndex()) {
                val dot = dx * d.first + dy * d.second
                if (dot > best) { best = dot; index = i }
            }
            return index
        }
        return Array(width) { x ->
            IntArray(height) { y ->
                val (cx, cy) = geometry.center(x, y)
                var closest = Int.MAX_VALUE
                var nextClosest = Int.MAX_VALUE
                for (seed in seeds) {
                    var distance = geometry.distance(x, y, seed.x, seed.y)
                    if (biased) {
                        distance += seed.weakness
                        distance += rng.nextInt(3)
                        val (sx, sy) = geometry.center(seed.x, seed.y)
                        val direction = estimateDirection(sx - cx, sy - cy)
                        if (direction == seed.direction) distance -= seed.strength
                        else if (direction == (seed.direction + 3) % 6) distance += seed.strength
                        distance = maxOf(1, distance)
                    }
                    if (distance < closest) { nextClosest = closest; closest = distance }
                    else if (distance < nextClosest) nextClosest = distance
                }
                val ridge = (255 * closest) / nextClosest
                (ridge * blendRidge + base.getHeight(x, y) * blendFract) / maxOf(blendRidge + blendFract, 1)
            }
        }
    }
}

/**
 *  The hexes of an Unciv map as a [HexGeometry]: the cell of a tile is its column and its row (the floor of half of x + y, so the odd
 *  columns are half a row lower than the even ones), counted from the smallest of each. Flat hexes: a column is a straight line.
 */
class UncivHexGeometry(private val tileMap: com.unciv.logic.map.TileMap) : HexGeometry {
    private val tiles = tileMap.values.toList()
    private val minColumn = tiles.minOf { it.getColumn() }
    private val minRow = tiles.minOf { cellRow(it) }
    override val width = tiles.maxOf { it.getColumn() } - minColumn + 1
    override val height = tiles.maxOf { cellRow(it) } - minRow + 1
    private val tileAt = HashMap<Pair<Int, Int>, com.unciv.logic.map.tile.Tile>()

    init {
        for (tile in tiles) tileAt[cellOf(tile)] = tile
    }

    private fun cellRow(tile: com.unciv.logic.map.tile.Tile) = Math.floorDiv(tile.position.x + tile.position.y, 2)

    fun cellOf(tile: com.unciv.logic.map.tile.Tile) = (tile.getColumn() - minColumn) to (cellRow(tile) - minRow)

    fun tileOf(x: Int, y: Int) = tileAt[x to y]

    override fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> =
        tileAt[x to y]?.neighbors?.map { cellOf(it) }?.toList() ?: emptyList()

    override fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val a = tileAt[x1 to y1] ?: return Int.MAX_VALUE
        val b = tileAt[x2 to y2] ?: return Int.MAX_VALUE
        return a.aerialDistanceTo(b)
    }

    override fun center(x: Int, y: Int): Pair<Double, Double> {
        val column = x + minColumn
        return column * 0.866025 to (y + 0.5 * Math.floorMod(column, 2))
    }

    override val directions = listOf(0.0 to 1.0, 0.866025 to 0.5, 0.866025 to -0.5, 0.0 to -1.0, -0.866025 to -0.5, -0.866025 to 0.5)
}
